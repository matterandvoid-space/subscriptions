(ns space.matterandvoid.subscriptions.xtdb-eql
  (:require
    [clojure.java.io :as io]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.core :refer [reg-sub-raw reg-sub <sub]]
    [space.matterandvoid.subscriptions.impl.eql-queries :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [taoensso.timbre :as log]
    [xtdb.api :as xt]))

(def query-key impl/query-key)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

;; so the idea is ....
;; implement graph walking algos using the subscriptions.
;; current design flaw is that it uses stack recursion
;; can look into using loom maybe?
;;
;; what is the goal?
;; what problem are you trying to solve?

;; I want to run arbitrary graph walking algorithms to implement applications with
;; simple api for performing complex graph queries involving walking and recursion
;; like i have a document tree model, where each document is composed of many entities recursively
;; I want to "pull" only some of those entities
;; in a nested fashion following some set of criteria and get that data back in a tree shape (nested), not normalized.

(defn xt-node? [n] (satisfies? xt/DBProvider n))
(defn db? [x] (satisfies? xt/PXtdbDatasource x))

(defn ->db [node-or-db]
  (let [v (if (r/ratom? node-or-db) @node-or-db node-or-db)]
    (cond
      (xt-node? v) (xt/db v)
      (db? v) v
      :else (throw (Exception. (str "Unsopported value passed to ->db: " (pr-str node-or-db)))))))

(def xtdb-data-source
  (reify impl/IDataSource
    (-ref->id [_ ref]
      (cond (eql/ident? ref)
            (second ref)
            (keyword? ref) ref
            :else ref))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ xt-node-or-db id-attr args]
      (log/info "xtdb lookup -entity, id-attr: " id-attr " value: " (get args id-attr))
      (try
        (xt/entity (->db xt-node-or-db) (get args id-attr))
        (catch IllegalArgumentException e)))
    (-attr [_ xt-node-or-db id-attr attr args]
      (log/info "-attr: " id-attr " attr " attr)
      (get (xt/entity (->db xt-node-or-db) (get args id-attr)) attr))))

(defn nc
  "Wraps fulcro.raw.components/nc to take one hashmap of fulcro component options, supports :ident being a keyword.
  Args:
  :query - fulcro eql query
  :ident - kw or function
  :name -> same as :componentName
  Returns a fulcro component created by fulcro.raw.components/nc"
  [args] (impl/nc args))

(def get-query impl/get-query)
(def class->registry-key impl/class->registry-key)
(def get-ident impl/get-ident)

(defn reg-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component must have a name as well as any components in its query."
  [c] (impl/reg-component-subs! reg-sub-raw reg-sub <sub xtdb-data-source c))
