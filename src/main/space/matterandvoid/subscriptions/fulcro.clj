(ns space.matterandvoid.subscriptions.fulcro
  (:require
    [cljs.analyzer :as ana]
    [cljs.env :as cljs-env]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.components :as c]))

;(defn get-fulcro-component-query-keys
;  []
;  (let [query-nodes        (some-> class (rc/get-query) (eql/query->ast) :children)
;        query-nodes-by-key (into {}
;                             (map (fn [n] [(:dispatch-key n) n]))
;                             query-nodes)
;        {props :prop joins :join} (group-by :type query-nodes)
;        join-keys          (->> joins (map :dispatch-key) set)
;        prop-keys          (->> props (map :dispatch-key) set)]
;    {:join join-keys :leaf prop-keys}))

;; copied query handling from fulcro.form-state.derive-form-info
;(defn component->subscriptions
;  "todo
;  The idea here is to register subscriptions for the given component based on its query to reduce boilerplate.
;   This can be a normal function because reg-sub operates at runtime"
;  [com])

(defmacro defsub
  "Has the same function signature as `reg-sub`.
  Registers a subscription and creates a function which is invokes subscribe and deref on the registered subscription
  with the args map passed in."
  [sub-name & args]
  (let [sub-kw (keyword (str *ns*) (str sub-name))]
    `(do
       (reg-sub ~sub-kw ~@args)

       (defn ~sub-name
         ([app#] (deref (subscribe app# [~sub-kw])))
         ([app# args#] (deref (subscribe app# [~sub-kw args#])))))))
