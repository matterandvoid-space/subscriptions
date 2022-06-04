(ns space.matterandvoid.subscriptions.impl.xtdb-queries
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [integrant.core :as ig]
    [space.matterandvoid.subscriptions.impl.fulcro-queries :as impl]
    [taoensso.timbre :as log]
    [xtdb.api :as xt])
  (:import [xtdb.api DBProvider]
           [xtdb.query QueryDatasource]))

(def lmdb-xt-config
  {:xtdb/index-store    {:kv-store {:xtdb/module 'xtdb.lmdb/->kv-store :db-dir (io/file "tmp/lmdb-index-store")}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.lmdb/->kv-store :sync? true :db-dir (io/file "tmp/lmdb-doc-store")}}
   :xtdb/tx-log         {:kv-store {:xtdb/module 'xtdb.lmdb/->kv-store :sync? true :db-dir (io/file "tmp/lmdb-tx-log-store")}}})

(def config
  {:xtdb/node lmdb-xt-config})

(defmethod ig/init-key :xtdb/node
  [_ opts]
  (log/info "opts: " opts)
  (xt/start-node opts))

(defmethod ig/halt-key! :xtdb/node [_ node]
  (log/info "Stopping xt node")
  (.close node))

(comment
  (xt/submit-tx (:xtdb/node system) [[::xt/put {:xt/id :hi :dan 5}]])
  (xt/entity (xt/db (:xtdb/node system)) :hi)
  (def system (ig/init config))
  (ig/halt! system)
  )

;;
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

(defn xt-node? [n] (instance? DBProvider n))
(defn db? [x] (or (instance? QueryDatasource x) (.isInstance QueryDatasource x)))
(defn ->db [node-or-db]
  (cond
    (xt-node? node-or-db) (xt/db node-or-db)
    (db? node-or-db) node-or-db
    :else (throw (Exception. (str "Unsopported value passed to ->db: " (pr-str node-or-db))))))

(def xtdb-data-source
  (reify impl/IDataSource
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ xt-node-or-db id-attr args]
      (xt/entity (->db xt-node-or-db) (get args id-attr)))
    (-attr [_ xt-node-or-db id-attr attr args]
      (get (xt/entity (->db xt-node-or-db) (get args id-attr)) attr))))

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
  [c] (impl/reg-component-subs! xtdb-data-source c))

