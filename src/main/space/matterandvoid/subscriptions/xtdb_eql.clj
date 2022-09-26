(ns space.matterandvoid.subscriptions.xtdb-eql
  (:require
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.core :refer [reg-sub-raw reg-sub <sub]]
    [space.matterandvoid.subscriptions.impl.eql-queries :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r :refer [make-reaction]]
    [space.matterandvoid.subscriptions.impl.eql-protocols :as proto]
    [taoensso.timbre :as log]
    [xtdb.api :as xt]))

(def query-key impl/query-key)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

(defn xt-node? [n] (satisfies? xt/DBProvider n))
(defn db? [x] (satisfies? xt/PXtdbDatasource x))

(defn ->db [node-or-db]
  (let [v (if (r/ratom? node-or-db) @node-or-db node-or-db)]
    (cond
      (xt-node? v) (xt/db v)
      (db? v) v
      :else (throw (Exception. (str "Unsupported value passed to ->db: " (pr-str node-or-db)))))))

(def xtdb-data-source
  (reify proto/IDataSource
    (-attribute-subscription-fn [this id-attr attr]
      (fn [db_ args]
        (make-reaction
          (fn []
            (impl/missing-id-check! id-attr attr args)
            (impl/-attr this db_ id-attr attr args)))))
    (-ref->attribute [_ ref] :xt/id)
    (-ref->id [_ ref]
      (cond (eql/ident? ref)
            (second ref)
            (keyword? ref) ref
            (and (map? ref) (contains? ref :xt/id)) (:xt/id ref)
            :else ref))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ xt-node-or-db id-attr args]
      ;(log/debug "xtdb lookup -entity, id-attr: " id-attr " value: " (get args id-attr))
      (try
        (xt/entity (->db xt-node-or-db) (get args id-attr))
        (catch IllegalArgumentException _e)))
    (-attr [_ xt-node-or-db id-attr attr args]
      ;(log/debug "-attr: " id-attr " attr " attr)
      (get (xt/entity (->db xt-node-or-db) (get args id-attr)) attr))))

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

(defn register-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component must have a name as well as any components in its query."
  [c] (impl/register-component-subs! reg-sub-raw reg-sub <sub xtdb-data-source c))
