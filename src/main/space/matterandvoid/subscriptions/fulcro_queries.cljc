(ns space.matterandvoid.subscriptions.fulcro-queries
  (:require
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.impl.fulcro-queries :as impl]))

(defn ->db [fulcro-app-or-db]
  (cond-> fulcro-app-or-db
    (fulcro.app/fulcro-app? fulcro-app-or-db)
    (fulcro.app/current-state)))

(def fulcro-data-source
  (reify impl/IDataSource
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
  The component must have a name as well as any components in its query."
  [c] (impl/reg-component-subs! fulcro-data-source c))

