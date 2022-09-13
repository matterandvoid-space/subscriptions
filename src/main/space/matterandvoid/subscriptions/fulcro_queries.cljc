(ns space.matterandvoid.subscriptions.fulcro-queries
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.fulcro :refer [reg-sub-raw reg-sub <sub]]
    [space.matterandvoid.subscriptions.impl.fulcro-queries :as impl]
    [taoensso.timbre :as log]))

(def query-key impl/query-key)
(def cycle-marker impl/cycle-marker)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

(defn ->db [fulcro-app-or-db]
  (cond-> fulcro-app-or-db
    (fulcro.app/fulcro-app? fulcro-app-or-db)
    (fulcro.app/current-state)))

(def fulcro-data-source
  (reify impl/IDataSource
    (-ref-value? [_ _ value] (eql/ident? value))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ fulcro-app id-attr args]
      (log/info "-entity for id attr: " id-attr)
      (get-in (->db fulcro-app) [id-attr (get args id-attr)]))
    (-attr [_ fulcro-app id-attr attr args]
      (get-in (->db fulcro-app) [id-attr (get args id-attr) attr]))))

(defn nc
  "Wraps fulcro.raw.components/nc to take one hashmap of fulcro component options, supports :ident being a keyword.
  Args:
  :query - fulcro eql query
  :ident - kw or function
  :name -> same as :componentName
  Returns a fulcro component created by fulcro.raw.components/nc"
  [args] (impl/nc args))

(defn reg-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component and any components in its query must have a name (cannot be anonymous)."
  [c] (impl/reg-component-subs! reg-sub-raw reg-sub <sub fulcro-data-source c))
