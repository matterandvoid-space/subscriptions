(ns space.matterandvoid.subscriptions.impl.core
  (:require
    [space.matterandvoid.subscriptions.impl.loggers :refer [console]]
    [space.matterandvoid.subscriptions.impl.shared :refer [memoize-fn]]
    [space.matterandvoid.subscriptions.impl.subs :as subs]
    [taoensso.timbre :as log]))

(defn get-input-db-signal [ratom] ratom)
(defonce subs-cache_ (atom {}))
(defn get-subscription-cache [_app] subs-cache_)
(defn cache-lookup [app query-v] (when app (get @(get-subscription-cache app) query-v)))
(defn subs-state-path [k] [k])
(defonce handler-registry_ (atom {}))

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [id handler-fn]
  (swap! handler-registry_ assoc-in (subs-state-path id) (fn [& args] (apply handler-fn args)))
  handler-fn)

(defn get-handler [id] (get-in @handler-registry_ (subs-state-path id)))

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

(defn reg-sub
  [query-id & args]
  (apply subs/reg-sub
    get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup memoize-fn
    query-id args))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. Also known as a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced"
  [app query]
  (subs/subscribe get-handler cache-lookup get-subscription-cache app query))

(defn <sub
  "Subscribe and deref a subscription, returning its value, not a reaction."
  [app query]
  (let [value (subscribe app query)]
    (when value @value)))

(defn clear-sub
  ([registry]
   (clear-handlers registry))
  ([registry query-id]
   (clear-handlers registry query-id)))

(defn reg-sub-raw [query-id handler-fn] (register-handler! query-id handler-fn))

(defn clear-subscription-cache!
  "Removes all subscriptions from the cache."
  [registry]
  (subs/clear-subscription-cache! get-subscription-cache registry))

