(ns space.matterandvoid.subscriptions.datalevin-eql
  (:require
    [clojure.java.io :as io]
    [datascript.impl.entity :as d.entity]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [datalevin.core :as d]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.core :refer [reg-sub-raw reg-sub <sub]]
    [space.matterandvoid.subscriptions.impl.eql-queries :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [taoensso.timbre :as log]))

(def query-key impl/query-key)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

(defn ->db [conn-or-db]
  (let [v (if (r/ratom? conn-or-db) @conn-or-db conn-or-db)]
    (cond
      (d/conn? v) (d/db v)
      (d/db? v) v
      :else (throw (Exception. (str "Unsopported value passed to ->db: " (pr-str conn-or-db)))))))

(def datalevin-data-source
  (reify impl/IDataSource
    (-ref->attribute [_ ref] :db/id)
    (-ref->id [_ ref]
      ;(log/info "ref->id ref: " ref)
      (cond (and (map? ref) (contains? ref :db/id))
            (:db/id ref)
            ;(eql/ident? ref)
            ;(:db/id (d/touch (d/entity (->db conn-or-db) ref)))
            :else ref))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ conn-or-db id-attr args]
      (log/info "datalevin lookup -entity, id-attr: " id-attr " value: " (get args id-attr))
      (try
        (let [id-val (get args id-attr)]
          (if (number? id-val)
            (d/touch (d/entity (->db conn-or-db) id-val))
            (when id-val
              (do
                (try
                  ;(println "in first try" id-val)
                  (if (eql/ident? id-val)
                    (d/touch (d/entity (->db conn-or-db) id-val))
                    ;; todo can check if id-val is a primitive type (allowed in value position, this way we don't have to use
                    ;; the catch, can just return nil, should be slightly faster.
                    (d/touch (d/entity (->db conn-or-db) [id-attr id-val])))
                  (catch AssertionError e
                    (println "in catch")
                    (d/touch (d/entity (->db conn-or-db) [id-attr id-val])))
                  (catch clojure.lang.ExceptionInfo e (println "in catch 2")))))))
        (catch ClassCastException e
          ;(println "class cast exc, return nil for id " (get args id-attr))
          )))
    (-attr [this conn-or-db id-attr attr args]
      ;(log/info "-attr: " id-attr " attr " attr " id: " (get args id-attr))
      (get (impl/-entity this conn-or-db id-attr args) attr))))

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
  [c] (impl/register-component-subs! reg-sub-raw reg-sub <sub datalevin-data-source c))
