(ns netops.advisor
  "Network Advisor client -- the *contained intelligence node* for the
  network-operator actor.

  It normalizes demand intake, drafts a per-jurisdiction right-of-way/
  carrier-license evidence checklist, screens demands for route
  capacity (by actually running `apn.rwa/assign` against the live
  topology -- not a self-report), drafts the lightpath-provisioning
  action, and drafts the lightpath-teardown action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  lightpath provisioning/teardown. Every output is censored downstream
  by `netops.governor` before anything touches the SSoT, and
  :actuation/provision-lightpath / :actuation/teardown-lightpath
  proposals NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/provision-lightpath | :actuation/teardown-lightpath | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [netops.facts :as facts]
            [netops.registry :as registry]
            [netops.store :as store]
            [apn.rwa :as rwa]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the demand, src/dst nodes or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "回線要求更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :demand/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-license
  "Per-jurisdiction right-of-way/carrier-license evidence checklist
  draft. `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `netops.facts` -- the Network Provisioning Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [dm (store/demand db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction dm))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "netops.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :license/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :license/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-route
  "Route-capacity screening draft. Actually RUNS `apn.rwa/assign`
  against the live topology -- not a self-report -- so a demand
  routed through a spectrum-exhausted link (e.g. `demo-topology`'s
  osaka-sapporo) genuinely comes back :blocked, injecting the failure
  mode the Network Provisioning Governor must HOLD, un-overridably,
  on."
  [db {:keys [subject]}]
  (let [dm (store/demand db subject)]
    (if (nil? dm)
      {:summary "対象回線要求が見つかりません" :rationale "no demand record"
       :cites [] :effect :route-screen/set :value {:demand-id subject :verdict :unknown}
       :stake nil :confidence 0.0}
      (let [topo (store/topology db)
            r (rwa/assign topo (:src dm) (:dst dm))]
        (if (:apn/ok? r)
          {:summary    (str (:customer-name dm) ": 経路確保可能 ("
                            (:apn/path r) " / λ" (:apn/wavelength r) ")")
           :rationale  "apn.rwa/assign が経路と共通波長を発見"
           :cites      [:apn-rwa]
           :effect     :route-screen/set
           :value      {:demand-id subject :verdict :available
                        :path (:apn/path r) :wavelength (:apn/wavelength r)}
           :stake      nil
           :confidence 0.9}
          {:summary    (str (:customer-name dm) ": 経路確保不可 (" (:apn/reason r) ")")
           :rationale  "apn.rwa/assign が経路または共通波長を発見できず"
           :cites      [:apn-rwa]
           :effect     :route-screen/set
           :value      {:demand-id subject :verdict :blocked :reason (:apn/reason r)}
           :stake      nil
           :confidence 0.95})))))

(defn- propose-provision-lightpath
  "Draft the actual LIGHTPATH-PROVISIONING action -- activating a real
  optical circuit for a demand. ALWAYS `:stake :actuation/provision-
  lightpath` -- this is a REAL-WORLD act, never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`netops.phase`); the governor also always
  escalates on `:actuation/provision-lightpath`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [dm (store/demand db subject)]
    {:summary    (str subject " 向けライトパス開通提案"
                      (when dm (str " (customer=" (:customer-name dm) ")")))
     :rationale  (if dm
                   (str "src=" (:src dm) " dst=" (:dst dm))
                   "回線要求が見つかりません")
     :cites      (if dm [subject] [])
     :effect     :demand/mark-provisioned
     :value      {:demand-id subject}
     :stake      :actuation/provision-lightpath
     :confidence (if (and dm (not (registry/route-endpoints-missing? (store/topology db) dm))) 0.9 0.3)}))

(defn- propose-teardown-lightpath
  "Draft the actual LIGHTPATH-TEARDOWN action -- releasing a real
  optical circuit for a demand. ALWAYS `:stake :actuation/teardown-
  lightpath` -- this is a REAL-WORLD act (and, like `telecom.
  telecomadvisor`'s billing-suppression, a NEGATIVE one -- releasing a
  circuit, not issuing one), never a draft the actor may auto-run."
  [db {:keys [subject]}]
  (let [dm (store/demand db subject)]
    {:summary    (str subject " 向けライトパス閉塞提案"
                      (when dm (str " (customer=" (:customer-name dm) ")")))
     :rationale  (if dm "route-screen チェックリスト参照" "回線要求が見つかりません")
     :cites      (if dm [subject] [])
     :effect     :demand/mark-torn-down
     :value      {:demand-id subject}
     :stake      :actuation/teardown-lightpath
     :confidence (if dm 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :demand/intake                     (normalize-intake db request)
    :license/verify                    (verify-license db request)
    :route/screen                      (screen-route db request)
    :actuation/provision-lightpath     (propose-provision-lightpath db request)
    :actuation/teardown-lightpath      (propose-teardown-lightpath db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは光通信ネットワーク事業者のライトパス開通・閉塞エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:demand/upsert|:license/set|:route-screen/set|"
       ":demand/mark-provisioned|:demand/mark-torn-down) "
       ":stake(:actuation/provision-lightpath か :actuation/teardown-lightpath か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [subject]}] {:demand (store/demand st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Network Provisioning
  Governor escalates/holds -- an LLM hiccup can never auto-provision or
  auto-teardown a lightpath."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
