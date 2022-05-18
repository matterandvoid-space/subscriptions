(ns space.matterandvoid.subscriptions.impl.fulcro
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as ftx]
    [com.fulcrologic.fulcro.algorithms.normalized-state :refer [dissoc-in]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c]
    [goog.object :as obj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.impl.loggers :refer [console]]
    [space.matterandvoid.subscriptions.impl.subs :as subs]
    [taoensso.timbre :as log]))

(defn get-input-db-signal
  "Given the storage for the subscriptions return an atom containing a map
  (this it the 'db' in re-frame parlance).
  this assumes you've set your fulcro app's state-atom to be reagent reactive atom."
  [app]
  (::fulcro.app/state-atom app))

;; for other proxy interfaces (other than fulcro storage) this has to be an atom of a map.
;; this is here for now just to inspect it at the repl
(defonce subs-cache (atom {}))
;; here we also have the option of storing the subscription cache in the fulcro app.
;; that way it will be visible to fulcro users
;; (you probably want it nested one level like under ::subscriptions key or something
;; so there isn't an explosion in the top level keyspace
;; it's a tradeoff, it may make more sense to just add integration with fulcro inspect via the
;; existing tracing calls.
(defn get-subscription-cache [app] subs-cache #_(atom {}))
(defn cache-lookup [app query-v] (when app (get @(get-subscription-cache app) query-v)))

(def app-state-key ::state)
(def subs-key ::subs)
(defn subs-state-path [k v] [app-state-key subs-key v])
(defn state-path ([] [app-state-key]) ([k] [app-state-key k]) ([k v] [app-state-key k v]))

(defonce handler-registry_ (atom {}))

(defn get-handler
  "Returns a \"handler\" function registered for the subscription with the given `id`.
  Fulcro app and 'query-id' -> subscription handler function.
  Lookup in the place where the query-id -> handler functions are stored."
  [id]
  (get-in @handler-registry_ (subs-state-path subs-key id)))

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [id handler-fn]
  ;(log/info "Registering handler: " id)
  (swap! handler-registry_ assoc-in (subs-state-path subs-key id)
    (fn [& args]
      ;(log/info "Calling handler with args: " args)
      ;(js/console.log "-------------------------------Calling handler with args: " args)
      (apply handler-fn args)))
  handler-fn)

(defn clear-handlers
  ;; clear all handlers
  ([db] (assoc db subs-key {}))
  ([db id]
   (if (get-handler id)
     (dissoc-in db (subs-state-path subs-key id))
     (console :warn "Subscriptions: can't clear handler for" (str id ". Handler not found.")))))

;; -------------------
;; api

(defn set-memoize-fn! [f] (subs/set-memoize-fn! f))
(defn set-args-merge-fn! [f] (subs/set-args-merge-fn! f))

(defn reg-sub
  [query-id & args]
  (apply subs/reg-sub
    get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
    query-id args))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. Also known as a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced"
  [?app query]
  (subs/subscribe get-handler cache-lookup get-subscription-cache (c/any->app ?app) query))

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

(defn clear-subscription-cache! [registry] (subs/clear-subscription-cache! get-subscription-cache registry))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reactive refresh of components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state-key "fulcro.subscriptions.state")
(def reaction-key "fulcro.subscriptions.reaction")

(defn refresh-component! [^js this]
  (when (c/mounted? this)
    (log/debug "Refreshing component" (c/component-name this))
    (.forceUpdate this)))

(defn get-component-reaction [this]
  (obj/get this reaction-key))

(defn reaction-callback [this]
  (log/debug "!! Attempting to refresh component" (c/component-name this))
  (let [fulcro-runtime-atom_ (::fulcro.app/runtime-atom (c/any->app this))
        counter_             (volatile! 0)
        max-loop             100
        attempt-to-draw
                             (fn attempt-to-draw []
                               (if (empty? (::ftx/submission-queue @fulcro-runtime-atom_))
                                 (do
                                   ;(log/info "no TXes, refreshing component" (c/component-name (c/get-class this)))
                                   (js/requestAnimationFrame (fn [_]
                                                               ;(log/info "Refreshing component" (c/component-name this))
                                                               (refresh-component! this))))
                                 (do
                                   ;(log/debug "NOT empty, looping")
                                   (vswap! counter_ inc)
                                   (if (< @counter_ max-loop)
                                     (js/setTimeout attempt-to-draw 16.67)
                                     ;; we probably want to throw in this case:
                                     ;(throw)
                                     (log/debug "Max retries reached, not looping.")))))]
    (attempt-to-draw)))

(defn remove-reaction! [this]
  (obj/remove this reaction-key))

(defn dispose-current-reaction!
  [this]
  (when-let [reaction (get-component-reaction this)]
    (log/info "Disposing current reaction: " reaction)
    (ratom/dispose! reaction)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public api for components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cleanup! "Intended to be called when a component unmounts to clear the registered Reaction." [this]
  ;(log/info " cleaning up reaction: " this)
  (dispose-current-reaction! this)
  (remove-reaction! this))

(defn setup-reaction!
  "Installs a Reaction on the provided component which will re-render the component when any of the subscriptions'
   values change."
  [this client-render]
  (when (nil? (get-component-reaction this))
    (log/debug "RUNNING IN REACTION")
    (ratom/run-in-reaction
      (fn [] (client-render this)) this reaction-key reaction-callback {:no-cache true})))
