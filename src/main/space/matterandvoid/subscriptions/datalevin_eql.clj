(ns space.matterandvoid.subscriptions.datalevin-eql
  (:require
    [borkdude.dynaload :refer [dynaload]]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.core :as subs :refer [reg-sub-raw <sub sub-fn]]
    [space.matterandvoid.subscriptions.impl.eql-queries :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [space.matterandvoid.subscriptions.impl.eql-protocols :as proto]))

(def query-key impl/query-key)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

(def d-conn (dynaload 'datalevin.core/conn))
(def d-conn? (dynaload 'datalevin.core/conn?))
(def d-db (dynaload 'datalevin.core/db))
(def d-db? (dynaload 'datalevin.core/db?))
(def d-touch (dynaload 'datalevin.core/touch))
(def d-entity (dynaload 'datalevin.core/entity))

(defn ->db [conn-or-db]
  (let [v (if (r/ratom? conn-or-db) @conn-or-db conn-or-db)]
    (cond
      (d-conn? v) (d-db v)
      (d-db? v) v
      :else (throw (Exception. (str "Unsupported value passed to ->db: " (pr-str conn-or-db)))))))

(defn map->id-ref [m] (first (filter (fn [[k]] (= (name k) "id")) (into {} m))))

(def datalevin-data-source
  (reify proto/IDataSource
    (-attribute-subscription-fn [this id-attr attr]
      (fn [db_ args]
        (r/make-reaction
          (fn []
            (impl/missing-id-check! id-attr attr args)
            (proto/-attr this db_ id-attr attr args)))))
    (-ref->attribute [_ ref]
      (if (map? ref) (first (map->id-ref ref)) :db/id))
    (-ref->id [_ ref]
      (cond (map? ref)
            (if-let [attr-ref (map->id-ref ref)]
              (second attr-ref)
              (:db/id ref))
            :else ref))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ conn-or-db id-attr args]
      (try
        (let [db     (->db conn-or-db)
              id-val (get args id-attr)]
          (if (number? id-val)
            (d-touch (d-entity db id-val))
            (when (and id-val (or (eql/ident? id-val) (not (coll? id-val))))
              (try
                (if (eql/ident? id-val)
                  (d-touch (d-entity db id-val))
                  (d-touch (d-entity db [id-attr id-val])))
                (catch AssertionError e
                  (d-touch (d-entity db [id-attr id-val])))
                (catch clojure.lang.ExceptionInfo e)))))
        (catch ClassCastException e)))
    (-attr [this conn-or-db id-attr attr args]
      (get (proto/-entity this conn-or-db id-attr args) attr))))

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
  ([component] (create-component-subs component {}))
  ([component sub-joins-map] (impl/create-component-subs ::subs/sub-name <sub sub-fn datalevin-data-source component sub-joins-map)))

(defn register-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component must have a name as well as any components in its query."
  [c] (impl/register-component-subs! reg-sub-raw <sub datalevin-data-source c))
