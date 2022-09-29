(ns space.matterandvoid.subscriptions.fulcro-eql
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.fulcro :refer [reg-sub-raw reg-sub <sub sub-fn]]
    [space.matterandvoid.subscriptions.impl.eql-queries :as impl]
    [space.matterandvoid.subscriptions.impl.eql-protocols :as proto]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :refer [cursor]]
    [taoensso.timbre :as log]))

(def query-key impl/query-key)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

(defn ->db [fulcro-app-or-db]
  (cond-> fulcro-app-or-db
    (fulcro.app/fulcro-app? fulcro-app-or-db)
    (fulcro.app/current-state)))

(def fulcro-data-source
  (reify proto/IDataSource
    (-attribute-subscription-fn [_ id-attr attr]
      (fn [fulcro-app args]
        (cursor (::fulcro.app/state-atom fulcro-app) [id-attr (get args id-attr) attr])))
    (-ref->attribute [_ ref] (first ref))
    (-ref->id [_ ref]
      ;(log/debug "-ref->id ref" ref)
      (cond (eql/ident? ref) (second ref)
            (map? ref) (let [id-key (first (filter (comp #(= % "id") name) (keys ref)))]
                         ;(println "ID KEY: " id-key)
                         (get ref id-key))
            :else ref))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ fulcro-app id-attr args]
      ;(log/info "-entity for id attr: " id-attr)
      (when (eql/ident? [id-attr (get args id-attr)])
        ;(log/info "IDENT" [id-attr (get args id-attr)])
        (get-in (->db fulcro-app) [id-attr (get args id-attr)])))
    (-attr [_ fulcro-app id-attr attr args]
      (get-in (->db fulcro-app) [id-attr (get args id-attr) attr]))))

(defn nc
  "Wraps fulcro.raw.components/nc to take one hashmap of fulcro component options, supports :ident being a keyword.
  Args:
  :query - fulcro eql query
  :ident - keyword or function
  :name -> same as :componentName (fully qualified keyword or symbol)
  Returns a fulcro component (or a mock version if fulcro is not installed) created by fulcro.raw.components/nc"
  [args] (impl/nc args))

(def get-query impl/get-query)
(def class->registry-key impl/class->registry-key)
(def get-ident impl/get-ident)

(defn create-component-subs
  "Creates a subscription function that will fulfill the given fulcro component's query.
  The component and any components in its query must have a name (cannot be anonymous).
  the `sub-joins-map` argument is a hashmap whose keys are the join properties of the component and whose value is a
  subscription function for normal joins, and a nested hashmap for unions of the union key to subscription.
  You do not need to provide a subscription function for recursive joins."
  [c sub-joins-map] (impl/create-component-subs <sub sub-fn fulcro-data-source c sub-joins-map))

(defn register-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component and any components in its query must have a name (cannot be anonymous)."
  [c] (impl/register-component-subs! reg-sub-raw <sub fulcro-data-source c))
