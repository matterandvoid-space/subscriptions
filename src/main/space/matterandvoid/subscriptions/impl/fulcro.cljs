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

(defn set-memoize! [f] (subs/set-memoize! f))

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

;; component rendering integrations

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reactive refresh of components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state-key "fulcro.subscriptions.state")
(def reaction-key "fulcro.subscriptions.reaction")
(def signals-values-key "signals-values")
(def user-signals-key "user-signals")

(defn refresh-component! [^js this]
  (when (c/mounted? this)
    (log/info "Refreshing component" (c/component-name this))
    (.forceUpdate this)))

(defn get-subscription-state
  "Used to store the subscriptions the component is subscribed."
  [this]
  (let [state (obj/get this state-key)]
    (if (nil? state)
      (get-subscription-state (doto this (obj/set state-key #js{})))
      state)))

;(defn update-subscription-state!
;  "Mutates the provided js object to store new state."
;  [this new-state]
;  (obj/extend (get-subscription-state this) new-state))

(defn get-user-signals-map
  "Invokes the client provided signals function this is expected to return a map of keywords to
  vectors that represent subscriptions.

  This function invokes that function and returns the map - it does not deref the subscription values."
  [client-signals-key this]
  (when-let [signals-fn (client-signals-key (c/component-options (c/get-class this)))]
    (when-not (fn? signals-fn)
      (throw (js/Error. (str "Component " (c/component-name this) " signals must be a function, you provided: " (pr-str signals-fn) "\n"))))
    (let [out (signals-fn this (c/props this))]
      (when-not (map? out) (throw (js/Error. (str "Component " (c/component-name this) " signals function must return a hashmap, got: " (pr-str out) "\n"))))
      out)))

(defn set-subscription-signals-values-map!
  "Mutates the provided js object to store new state.

  Also stores the current subscription vectors as supplied by the component options. This is used to determine if these
  change over time. If they do then we need to dispose the reaction and setup a new one."
  [client-signals-key this signals-values-map]
  (doto (get-subscription-state this)
    (obj/set signals-values-key signals-values-map)
    (obj/set user-signals-key (get-user-signals-map client-signals-key this))))

(defn get-cached-signals-values
  "Return the map of values."
  [client-signals-key this]
  (when (nil? (get-user-signals-map client-signals-key this))
    (throw (js/Error. (str "Missing signals function on component:\n\n" (c/component-name this)
                        "\n\nYou need to provide a function in the shape: (fn [this props] {:a-property [::a-subscription]}}\n\n"
                        "At the key: " (pr-str client-signals-key) " on your component options.\n"))))
  (obj/get (get-subscription-state this) signals-values-key))

(defn get-cached-user-signals-map
  "Return the map of keyword->vectors as supplied on the component."
  [this]
  (obj/get (get-subscription-state this) user-signals-key))

(defn get-component-reaction [this]
  (obj/get this reaction-key))

(defn some-signals?
  "Returns true if the signals map is non-nil and if any of the current values are non-nil - false otherwise."
  [this user-signals-map]
  ;; need to test - might need to use sub/subscribe for the filter check instead of with the deref
  ;; this might just be sub/subscribe is the filter instead
  (and user-signals-map
    (->
      (->> (vals user-signals-map) (keep (partial <sub this)))
      seq boolean)))

(defn map-vals [f m] (into {} (map (juxt key (comp f val))) m))

(defn subscribe-and-deref-signals-map [client-signals-key this]
  (map-vals (partial <sub this) (get-user-signals-map client-signals-key this)))

(defn reaction-callback* [client-signals-key reaction-key this]
  (let [new-signal-values-map (subscribe-and-deref-signals-map client-signals-key this)
        current-signal-values (get-cached-signals-values client-signals-key this)]
    (comment
      (def this' this)
      (let [app (c/any->app this')]
        (::ftx/submission-queue (deref (::fulcro.app/runtime-atom app)))
        ;(sort (keys (deref (::fulcro.app/runtime-atom app))))
        )
      (c/component-name this')
      )
    ;(log/info "IN Reactive callback")
    ;(log/info "signals curr: " current-signal-values)
    ;(log/info "signals new: " new-signal-values-map)
    ;(log/info "did signals change: " (pr-str (not= new-signal-values-map current-signal-values)))
    (when (= new-signal-values-map current-signal-values)
      (log/debug "SIGNALS ARE NOT DIFFERENT comp: " (c/component-name this)))

    (when (not= new-signal-values-map current-signal-values)
      (do
        (log/debug "!! SIGNALS ARE DIFFERENT" (c/component-name this))
        ;; store the new subscriptions - the map -
        (log/debug " setting map to: " new-signal-values-map)
        (set-subscription-signals-values-map! client-signals-key this new-signal-values-map)
        ;; This prevents multiple re-renders from happening for the case where fulcro mutations are causing repaints
        ;; and the subscription would as well. We wait for the fulcro tx queue to empty before refreshing.
        (let [fulcro-runtime-atom_ (::fulcro.app/runtime-atom (c/any->app this))
              counter_             (volatile! 0)
              max-loop             100
              attempt-to-draw
              ;; todo investigate if batching makes sense and sorting by position in the react tree (run-queue in reagent)
              ;; could also handle de-duplication (one component using multiple subs which trigger re-rendering)
              ;; - not sure if this is already handled by how reactive atoms work but should investigate
              ;; https://github.com/reagent-project/reagent/blob/master/src/reagent/impl/batching.cljs
              ;; https://github.com/reagent-project/reagent/blob/master/doc/BatchingAndTiming.md
                                   (fn attempt-to-draw []
                                     (if (empty? (::ftx/submission-queue @fulcro-runtime-atom_))
                                       (do (log/info "no TXes, refreshing component" (c/component-name (c/get-class this)))
                                           (js/requestAnimationFrame (fn [_]
                                                                       ;(log/info "Refreshing component" (c/component-name this))
                                                                       (refresh-component! this))))
                                       (do
                                         (log/debug "NOT empty, looping")
                                         (vswap! counter_ inc)
                                         (if (< @counter_ max-loop)
                                           (js/setTimeout attempt-to-draw 16.67)
                                           (log/debug "Max retries reached, not looping.")))))]
          (attempt-to-draw))))))

(defn remove-reaction! [this]
  (obj/remove this reaction-key))

(defn dispose-current-reaction!
  [this]
  (when-let [reaction (get-component-reaction this)]
    (log/info "Disposing current reaction: " reaction)
    (ratom/dispose! reaction)))

(defn user-signals-map-changed?
  "Determines if the map of keywords to vectors representing subscriptions has changed, this is used to enable REPL
  development and for dynamically changing the subscriptions the component subscribes to dynamically."
  [client-signals-key this]
  (let [cached (get-cached-user-signals-map this)]
    (and cached
      (not= (get-user-signals-map client-signals-key this) cached))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public api for components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cleanup! "Intended to be called when a component unmounts to clear the registered Reaction." [this]
  ;(log/info " cleaning up reaction: " this)
  (dispose-current-reaction! this)
  (remove-reaction! this))

(defn setup-reaction-orig!
  "Installs a Reaction on the provided component which will re-render the component when any of the subscriptions'
   values change."
  [client-signals-key this client-render]
  (when (user-signals-map-changed? client-signals-key this)
    (log/debug "user signals changed! disposing !")
    (cleanup! this))

  (let [signals-map (get-user-signals-map client-signals-key this)]
    (when (and (nil? (get-component-reaction this)) (some-signals? this signals-map))
      (log/debug "RUNNING IN REACTION")
      (ratom/run-in-reaction (fn []
                               (set-subscription-signals-values-map! client-signals-key this
                                 (subscribe-and-deref-signals-map client-signals-key this))
                               (client-render this)) this reaction-key
        (fn reactive-run [_]
          (log/info "IN reactive run")
          (reaction-callback* client-signals-key reaction-key this))
        {:no-cache true}))))

(defn setup-reaction!
  "Installs a Reaction on the provided component which will re-render the component when any of the subscriptions'
   values change."
  [client-signals-key this client-render]
  (when (user-signals-map-changed? client-signals-key this)
    (log/debug "user signals changed! disposing !")
    (cleanup! this))

  (let [signals-map (get-user-signals-map client-signals-key this)]
    (when (and (nil? (get-component-reaction this)))
      (log/debug "RUNNING IN REACTION")
      (ratom/run-in-reaction (fn []
                               (set-subscription-signals-values-map! client-signals-key this
                                 (subscribe-and-deref-signals-map client-signals-key this))
                               (client-render this)) this reaction-key
        (fn reactive-run [_]
          (log/info "IN reactive run")
          (reaction-callback* client-signals-key reaction-key this))
        {:no-cache true}))))
