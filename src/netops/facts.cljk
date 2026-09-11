(ns netops.facts
  "Per-jurisdiction wired-telecommunications carrier-licensing / right-
  of-way regulatory catalog -- the spec-basis table the Network
  Provisioning Governor checks every :license/verify proposal against
  ('did the advisor cite an OFFICIAL public source for this
  jurisdiction's carrier-licensing / right-of-way authority, or did it
  invent one?'). Same shape and discipline as
  cloud-itonami-isic-6190's telecom.facts.

  Coverage is reported HONESTLY (see `coverage`): a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official carrier-
  licensing / right-of-way regulator (see `:provenance`); they are a
  STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source, done -- never invent a jurisdiction's
  requirements to make coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  carrier-registration / right-of-way-permit / circuit-provisioning-
  record / facility-maintenance-log evidence set submitted in some
  form; `:legal-basis` / `:owner-authority` / `:provenance` are the
  citation the governor requires before any :license/verify proposal
  can commit."
  {"JPN" {:name "Japan"
          :owner-authority "総務省 (MIC, Ministry of Internal Affairs and Communications)"
          :legal-basis "電気通信事業法 第9条(電気通信事業の登録) / 道路法 第32条(道路占用許可)"
          :national-spec "電気通信回線設備の設置・道路占用に関する規律"
          :provenance "https://www.soumu.go.jp/"
          :required-evidence ["電気通信事業者登録 (telecom-carrier-registration)"
                              "道路占用許可 (road-occupancy-right-of-way-permit)"
                              "回線敷設記録 (circuit-provisioning-record)"
                              "設備保全記録 (facility-maintenance-log)"]}
   "USA" {:name "United States"
          :owner-authority "Federal Communications Commission (FCC) / state Public Utility Commissions"
          :legal-basis "Communications Act of 1934, 47 U.S.C. §214 (Certificate of Public Convenience) / municipal right-of-way ordinances"
          :national-spec "Common-carrier authorization and right-of-way franchise requirements"
          :provenance "https://www.fcc.gov/wireline-competition"
          :required-evidence ["Common-carrier authorization record"
                              "Right-of-way franchise permit"
                              "Circuit-provisioning record"
                              "Facility-maintenance log"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Office of Communications (Ofcom)"
          :legal-basis "Communications Act 2003 / New Roads and Street Works Act 1991 (street works licence)"
          :national-spec "Electronic Communications Code apparatus rights and street-works notification"
          :provenance "https://www.ofcom.org.uk/networks-and-communications"
          :required-evidence ["Electronic Communications Code notice"
                              "Street-works licence"
                              "Circuit-provisioning record"
                              "Facility-maintenance log"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesnetzagentur"
          :legal-basis "Telekommunikationsgesetz (TKG) §68 (Wegerecht, right-of-way)"
          :national-spec "Wegenutzung / Leitungsrecht für Telekommunikationslinien"
          :provenance "https://www.bundesnetzagentur.de/DE/Fachthemen/Telekommunikation/"
          :required-evidence ["Betreibermeldung (carrier-notification record)"
                              "Wegerecht-Genehmigung (right-of-way permit)"
                              "Leitungsverlegungsnachweis (circuit-provisioning record)"
                              "Instandhaltungsprotokoll (facility-maintenance log)"]}
   ;; Spain -- verified 2026-07 directly against BOE-A-2022-10757 (full
   ;; consolidated text fetched from boe.es) and cnmc.es (both fetched live,
   ;; no bot-challenge encountered). Ley 11/2022 art. 6.2 confirms Spain uses
   ;; the EU general-authorization/notification regime (no individual
   ;; carrier licence) -- verified rather than assumed. cnmc.es's own
   ;; Registro de Operadores page independently corroborates the same
   ;; article number and registry role, cross-checking the BOE primary
   ;; source. Art. 45 (derecho de ocupación del dominio público) is the
   ;; closest analogue in this law to the other jurisdictions'
   ;; right-of-way/street-works permit, but note its access guarantee runs
   ;; to whoever "holds" the public domain in question (typically municipal
   ;; road authorities), not to CNMC itself -- same pattern as JPN's 道路法
   ;; road-occupancy permit not being administered by MIC either. A specific
   ;; universal-service-obligation designee or numbering/interconnection
   ;; circular citation was considered (per CNMC's own materials, CNMC does
   ;; run numbering assignment/portability circulars) but is NOT included
   ;; here: this catalog's fixed map shape has no field for it, and no
   ;; single verifiable citation for it was pinned down this session --
   ;; left out rather than guessed at.
   "ESP" {:name "Spain"
          :owner-authority "Comisión Nacional de los Mercados y la Competencia (CNMC) / Ministerio de Asuntos Económicos y Transformación Digital (procedimientos de ocupación de dominio público y propiedad)"
          :legal-basis "Ley 11/2022, de 28 de junio, General de Telecomunicaciones, art. 6.2 (notificación previa al Registro de Operadores; régimen de habilitación general, no licencia individual) / art. 45 (derecho de ocupación del dominio público)"
          :national-spec "Régimen de habilitación general (notificación, no licencia individual) y derecho de ocupación del dominio público para el despliegue de redes públicas de comunicaciones electrónicas"
          :provenance "https://www.cnmc.es/sectores-que-regulamos/telecomunicaciones/registro-de-operadores"
          :required-evidence ["Notificación al Registro de Operadores (carrier-notification record)"
                              "Derecho de ocupación del dominio público (right-of-way permit)"
                              "Registro de despliegue de red (circuit-provisioning record)"
                              "Registro de mantenimiento de instalaciones (facility-maintenance log)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to provision or
  tear down a lightpath on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6110 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `netops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3] (:required-evidence (spec-basis iso3) []))
