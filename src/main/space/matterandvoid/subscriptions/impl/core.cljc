(ns space.matterandvoid.subscriptions.impl.core
  (:require
    [space.matterandvoid.subscriptions.core :as-alias subs.core]
    [space.matterandvoid.subscriptions.impl.loggers :refer [console]]
    [space.matterandvoid.subscriptions.impl.subs :as subs]
    [taoensso.timbre :as log]))

(defn get-input-db-signal [ratom] ratom)
(defonce subs-cache_ (atom {}))
(defn get-subscription-cache [_app] subs-cache_)
(defn get-cache-key [datasource query-v] (if (keyword? (first query-v)) query-v (into [(hash datasource)] query-v)))
(defn cache-lookup [datasource cache-key] (when datasource (get @(get-subscription-cache datasource) cache-key)))
(defn subs-state-path [k] [k])
(defonce handler-registry_ (atom {}))

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [id handler-fn]
  (swap! handler-registry_ assoc-in (subs-state-path id) (fn [& args] (apply handler-fn args)))
  handler-fn)

(defn get-handler [id] (if (fn? id)
                         (or (-> id meta ::subs.core/subscription) id)
                         (get-in @handler-registry_ (subs-state-path id))))

(defn clear-handlers
  ;; clear all handlers
  ([_db] (reset! handler-registry_ {}))
  ([_db id]
   (if (get-handler id)
     (swap! handler-registry_ update dissoc id)
     ;(update db subs-key dissoc id)
     (console :warn "Subscriptions: can't clear handler for" (str id ". Handler not found.")))))

;----------
;; api

(defn set-memoize-fn! [f] (subs/set-memoize-fn! f))
(defn set-args-merge-fn! [f] (subs/set-args-merge-fn! f))

(defn reg-sub
  [query-id & args]
  (apply subs/reg-sub
    get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup get-cache-key
    query-id args))

(defn reg-layer2-sub
  [query-id path-vec-or-fn]
  (subs/reg-layer2-sub
    get-input-db-signal register-handler!
    query-id path-vec-or-fn))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. Also known as a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced"
  [app query]
  (subs/subscribe get-handler cache-lookup get-subscription-cache get-cache-key
    app query))

(defn <sub
  "Subscribe and deref a subscription, returning its value, not a reaction."
  [app query]
  (when-let [value (subscribe app query)] @value))

(defn clear-sub
  ([registry]
   (clear-handlers registry))
  ([registry query-id]
   (clear-handlers registry query-id)))

(defn reg-sub-raw [query-id handler-fn] (subs/reg-sub-raw register-handler! query-id handler-fn))

(defn clear-subscription-cache!
  "Removes all subscriptions from the cache."
  [registry]
  (subs/clear-subscription-cache! get-subscription-cache registry))

(defn parse-reg-sub-args [args]
  (subs/parse-reg-sub-args get-input-db-signal subscribe "space.matterandvoid.subscriptions: " args))

(def deref-input-signals subs/deref-input-signals)
