(ns space.matterandvoid.subscriptions.impl.fulcro
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalized-state :refer [dissoc-in]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as fulcro.render]
    [goog.functions :refer [debounce]]
    [goog.object :as obj]
    [reagent.ratom :as ratom]
    [space.matterandvoid.subscriptions.loggers :refer [console]]
    [space.matterandvoid.subscriptions.subs :as subs]
    [taoensso.timbre :as log]))

(defn get-input-db [app] (if app (fulcro.app/current-state app) (console :info "APP IS NULL")))

(defn get-input-db-signal
  "Given the storage for the subscriptions return an atom containing a map
  (this it the 'db' in re-frame parlance).
  this assumes you've set your fulcro app's state-atom to be reagent reactive atom."
  [app]
  ;(.log js/console " GET INPUT SIGNAL" (type (::fulcro.app/state-atom app)))
  (::fulcro.app/state-atom app))

;; for other proxy interfaces (other than fulcro storage) this has to be an atom of a map.
;; this is here for now just to inspect it at the repl
(defonce subs-cache (atom {}))
(defn get-subscription-cache [app] subs-cache #_(atom {}))
(defn cache-lookup [app query-v]
  (when app
    ;(console :error "subs. cache lookup: " query-v )
    ;(console :info "subs. cache:  "  @(get-subscription-cache app) )
    (def cache' @(get-subscription-cache app))
    (get @(get-subscription-cache app) query-v)
    ))

(def debug-enabled? false)

(def app-state-key ::state)
(def subs-key ::subs)
(defn subs-state-path [k v] [app-state-key subs-key v])
(defn state-path ([] [app-state-key]) ([k] [app-state-key k]) ([k v] [app-state-key k v]))

(defn get-handler
  "Returns a \"handler\" function registered for the subscription with the given `id`.
  Fulcro app and 'query-id' -> subscription handler function.
  Lookup in the place where the query-id -> handler functions are stored."
  ([app id]
   (log/info "GETTING HANDLER state is: " (get-in @(::fulcro.app/runtime-atom app) (state-path)))
   (get-in @(::fulcro.app/runtime-atom app) (subs-state-path subs-key id)))

  ([app id required?]
   (let [handler (get-handler app id)]
     (when debug-enabled? ;; This is in a separate `when` so Closure DCE can run ...
       (when (and required? (nil? handler)) ;; ...otherwise you'd need to type-hint the `and` with a ^boolean for DCE.
         (console :error "Subscription: no handler registered for:" id)))
     handler)))

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [app id handler-fn]
  (console :info "IN register-handler")
  (swap! (::fulcro.app/runtime-atom app) assoc-in (subs-state-path subs-key id) (fn [& args]
                                                                                  (log/info "IN HANDLER " id " args: " args)
                                                                                  (apply handler-fn args)))
  handler-fn)

(defn clear-handlers
  ;; clear all handlers
  ([db] (assoc db subs-key {}))
  ([db id]
   (if (get-handler db id)
     (dissoc-in db (subs-state-path subs-key id))
     ;(update db subs-key dissoc id)
     (console :warn "Subscriptions: can't clear handler for" (str id ". Handler not found.")))))

;; -- subscriptions -----------------------------------------------------------

(defn reg-sub
  "A call to `reg-sub` associates a `query-id` with two functions ->
  a function returning input signals and a function (the signals function)
  taking the input-signals current value(s) as input and returning a value (the computation function).

  The two functions provide 'a mechanism' for creating a node
  in the Signal Graph. When a node of type `query-id` is needed,
  the two functions can be used to create it.

  The three arguments are:

  - `query-id` - typically a namespaced keyword (later used in subscribe)
  - optionally, an `input signals` function which returns the input data
    flows required by this kind of node.
  - a `computation function` which computes the value (output) of the
    node (from the input data flows)

  It registers 'a mechanism' (the two functions) by which nodes
  can be created later, when a node is bought into existence by the
  use of `subscribe` in a `View Function`reg-sub.

  The `computation function` is expected to take two arguments:

    - `input-values` - the values which flow into this node (how is it wired into the graph?)
    - `query-vector` - the vector given to `subscribe`

  and it returns a computed value (which then becomes the output of the node)

  When `computation function` is called, the 2nd `query-vector` argument will be that
  vector supplied to the `subscribe`. So, if the call was `(subscribe [:sub-id 3 :blue])`,
  then the `query-vector` supplied to the computaton function will be `[:sub-id 3 :blue]`.

  The argument(s) supplied to `reg-sub` between `query-id` and the `computation-function`
  can vary in 3 ways, but whatever is there defines the `input signals` part
  of `the mechanism`, specifying what input values \"flow into\" the
  `computation function` (as the 1st argument) when it is called."
  [app_ query-id & args]
  ;; In some fulcro apps, the application is set asynchronously on boot of the application, this lets us capture its
  ;; current value - requires passing a Var as app though.

  ;; The client code needs to deal with when they want to register subscriptions - it doesn't have to be in toplevel
  ;; forms, they can be setup at will in a callback, like when you create a new fulcro app - then invoke
  ;; register-subs! - they need to be present for the components to read them before you mount the app or
  (if (var? app_)
    (do
      (log/debug "IS A VAR")
      (js/setTimeout
        (fn []
          (let [app (if (var? app_) @app_ app_)]
            (assert app)
            (apply subs/reg-sub
              get-input-db get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
              app query-id args)))))
    (let [app app_]
      (log/debug "IS NOT A VAR")
      (assert app)
      (apply subs/reg-sub
        get-input-db get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
        app query-id args))))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. Also known as a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced"
  [?app query]
  (subs/subscribe get-input-db get-handler cache-lookup get-subscription-cache (c/any->app ?app) query))

(defn <sub
  "Subscribe and deref a subscription, returning its value, not a reaction."
  [app query]
  (let [value (subscribe app query)]
    (log/debug "<sub value: " value)
    (when value @value)))

(defn clear-sub ;; think unreg-sub
  "Unregisters subscription handlers (presumably registered previously via the use of `reg-sub`).

  When called with no args, it will unregister all currently registered subscription handlers.

  When given one arg, assumed to be the `id` of a previously registered
  subscription handler, it will unregister the associated handler. Will produce a warning to
  console if it finds no matching registration.

  NOTE: Depending on the usecase, it may be necessary to call `clear-subscription-cache!` afterwards"
  {:api-docs/heading "Subscriptions"}
  ([registry]
   (clear-handlers registry))
  ([registry query-id]
   (clear-handlers registry query-id)))

(defn reg-sub-raw
  "This is a low level, advanced function.  You should probably be
  using `reg-sub` instead.

  Some explanation is available in the docs at
  <a href=\"http://day8.github.io/re-frame/flow-mechanics/\" target=\"_blank\">http://day8.github.io/re-frame/flow-mechanics/</a>"
  {:api-docs/heading "Subscriptions"}
  [registry query-id handler-fn]
  (register-handler! registry query-id handler-fn))

(defn clear-subscription-cache!
  "Removes all subscriptions from the cache.

  This function can be used at development time or test time. Useful when hot reloading
  namespaces containing subscription handlers. Also call it after a React/render exception,
  because React components won't have been cleaned up properly. And this, in turn, means
  the subscriptions within those components won't have been cleaned up correctly. So this
  forces the issue."
  [registry]
  (subs/clear-subscription-cache! get-subscription-cache registry))

;; component rendering integrations

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reactive refresh of components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state-key "fulcro.subscriptions.state")
(def reaction-key "fulcro.subscriptions.reaction")
(def signals-key "signals")
(def user-signals-key "user-signals")

(defn refresh-component!* [reaction-key this]
  (obj/set this "subscription-submit-for-refresh?" true)
  (log/info "Refreshing component" (obj/get this reaction-key))
  (.log js/console "IN RAF, refresh component: " this)
  (.log js/console "IN RAF, refresh component: " (c/get-ident this))
  (binding [c/*blindly-render* true] (c/refresh-component! this)))

(def refresh-component! (debounce refresh-component!* 13))

(defn get-subscription-state
  "Used to store the subscriptions the component is subscribed."
  [this]
  (let [state (obj/get this state-key)]
    (if (nil? state)
      (get-subscription-state (doto this (obj/set state-key #js{})))
      state)))

(defn update-subscription-state!
  "Mutates the provided js object to store new state."
  [this new-state]
  (obj/extend (get-subscription-state this) new-state))

(defn get-user-signals-map
  "Invokes the client provided signals function this is expected to return a map of keywords to
  vectors these represent subscriptions.

  This function invokes that function and returns the map - it does not deref the subscription values."
  [client-signals-key this]
  (let [signals-fn (client-signals-key (c/component-options (c/get-class this)))]
    (when (nil? signals-fn)
      (throw (js/Error. (str "Missing signals function on component:\n\n" (c/component-name this)
                          "\n\nYou need to provide a function in the shape: (fn [this props] {:name-1 [::a-subscription]}}\n\n"
                          "At the key: " (pr-str client-signals-key) " on your component options.\n"
                          ))))
    (signals-fn this (c/props this))))

(defn set-subscription-signals-values-map!
  "Mutates the provided js object to store new state.

  Also stores the current subscription vectors as supplied by the component options. This is used to determine if these
  change over time. If they do then we need to dispose the reaction and setup a new one."
  [client-signals-key this signals-values-map]
  (doto (get-subscription-state this)
    (obj/set signals-key signals-values-map)
    (obj/set user-signals-key (get-user-signals-map client-signals-key this))))

(defn get-cached-signals-map
  "Return the map of values."
  [this]
  (obj/get (get-subscription-state this) signals-key))

(defn get-cached-user-signals-map
  "Return the map of keyword->vectors as supplied on the component."
  [this]
  (obj/get (get-subscription-state this) user-signals-key))

(defn get-component-reaction [this]
  (obj/get this reaction-key))

(defn some-signals? [this user-signals-map]
  ;; need to test - might need to use sub/subscribe for the filter check instead of with the deref
  ;; this might just be sub/subscribe is the filter instead
  (-> (keep (partial <sub this) (vals user-signals-map)) seq boolean))

(defn map-vals [f m] (into {} (map (juxt key (comp f val))) m))

(defn subscribe-and-deref-signals-map [client-signals-key this]
  (map-vals (partial <sub this) (get-user-signals-map client-signals-key this)))

(defn reaction-callback* [client-signals-key this reaction-key]
  (let [new-signal-values-map (subscribe-and-deref-signals-map client-signals-key this)
        current-signal-values (get-cached-signals-map this)]
    (def this' this)
    (log/info "IN Reactive callback")
    (log/info "signals curr: " current-signal-values)
    (log/info "signals new: " new-signal-values-map)
    (log/info "did signals change: " (pr-str (not= new-signal-values-map current-signal-values)))
    (if (= new-signal-values-map current-signal-values)
      (log/info "SIGNALS ARE NOT DIFFERENT")
      (do
        (log/info "!! SIGNALS ARE DIFFERENT")
        ;; store the new subscriptions - the map -
        (set-subscription-signals-values-map! client-signals-key this new-signal-values-map)
        (js/requestAnimationFrame (fn [time] (refresh-component! reaction-key this)))))))

;; prevent multiple reactions triggering this callback in the same frame
(def reaction-callback (debounce reaction-callback* 15))

(defn remove-reaction! [this]
  (obj/remove this reaction-key))

(defn dispose-current-reaction!
  [this]
  (when-let [reaction (get-component-reaction this)]
    (log/info "Disposing current reaction: " reaction)
    (ratom/dispose! reaction)))

(defn user-signals-map-changed?
  "Determines if the map of keywords to vectors representing subscriptions has changed, this is used to enable repl
  development and for dynamically changing the subscriptions the component subscribes to dynamically."
  [client-signals-key this]
  (let [cached (get-cached-user-signals-map this)]
    (log/info "new user signals map " (get-user-signals-map client-signals-key this))
    (log/info "cached user signals map" cached)
    (and cached
      (not= (get-user-signals-map client-signals-key this) cached))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public api for components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cleanup! "Intended to be called when a component unmounts to clear the registered Reaction." [this]
  (log/info " cleaning up reaction: " this)
  (dispose-current-reaction! this)
  (remove-reaction! this))

(defn setup-reaction!
  "Installs a Reaction on the provided component which will re-render the component when any of the subscriptions'
   values change."
  [client-signals-key this client-render]
  (log/debug "setup-reaction!")
  (when (user-signals-map-changed? client-signals-key this)
    (log/debug "user signals changed! disposing !")
    (cleanup! this))

  (let [signals-map (get-user-signals-map client-signals-key this)]
    (log/debug "Current signals map: " signals-map)
    (when (and (nil? (get-component-reaction this)) (some-signals? this signals-map))
      (log/debug "RUNNING IN REACTION")
      (ratom/run-in-reaction (fn []
                               (set-subscription-signals-values-map! client-signals-key this
                                 (subscribe-and-deref-signals-map client-signals-key this))
                               (client-render this)) this reaction-key
        (fn reactive-run [this] (reaction-callback client-signals-key this reaction-key))
        {:no-cache true}))))