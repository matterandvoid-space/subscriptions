(ns re-frame-subs.fulcro
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.algorithms.normalized-state :refer [dissoc-in]]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as fulcro.render]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as c]
    [re-frame-subs.loggers :refer [console]]
    [taoensso.timbre :as log]
    [goog.object :as gobj]
    [reagent.ratom :as ratom]
    [re-frame-subs.subs :as subs]))

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

(def state-key ::state)
(def subs-key ::subs)
(defn subs-state-path [k v] [state-key subs-key v])
(defn state-path ([] [state-key]) ([k] [state-key k]) ([k v] [state-key k v]))

(defn copy-subscription-state!
  "Copy the subscriptions state in fulcro-app1 to fulcro-app2."
  [fulcro-app1 fulcro-app2]
  (swap! (::fulcro.app/runtime-atom fulcro-app2) assoc state-key
    (get-in @(::fulcro.app/runtime-atom fulcro-app1) (state-path))))

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
  (if (var? app_)
    (do
      (log/info "IS A VAR")
      (js/setTimeout
        (fn []
          (let [app (if (var? app_) @app_ app_)]
            (assert app)
            (apply subs/reg-sub
              get-input-db get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
              app query-id args)))))
    (let [app app_]
      (log/info "IS NOT A VAR")
      (assert app)
      (apply subs/reg-sub
        get-input-db get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
        app query-id args))
    )
  )
(comment

  (run! println
    (doto #js [1 2 3 4] (.push 100)))

  (.-length #js[1 23 3]))

(let [components-key ::components-to-update]
  (letfn [(get-components-to-refresh [app]
            (let [components (get-in @(::fulcro.app/runtime-atom app) (state-path components-key))]
              (log/info "components: " components)
              (if components components
                             (let [empty-components #js[]]
                               (swap! (::fulcro.app/runtime-atom app) update-in (state-path) assoc components-key empty-components)
                               empty-components))))
          (add-component-to-refresh! [app component]
            (.push (get-components-to-refresh app) component))]
    (defn subscribe
      "Given a `query` vector, returns a Reagent `reaction` which will, over
      time, reactively deliver a stream of values. Also known as a `Signal`.

      To obtain the current value from the Signal, it must be dereferenced"
      [?app query]
      (if false
        ;(c/component-instance? ?app)
        (let [^clj reactive-atom (gobj/get ?app "cljsRatom")
              component          ?app
              app                (c/any->app component)
              reaction-opts      {:no-cache true}
              orig-will-unmount  (.-componentWillUnmount component)
              unmount-set-var    "fulcro.subscriptions.unmountSet?"
              new-unmount
                                 (fn component-will-unmount []
                                   (when-not (gobj/get component unmount-set-var)
                                     (gobj/set component unmount-set-var true)
                                     (log/info "UNMOUNTING, calling dispose!")
                                     (some-> (gobj/get component "cljsRatom") ratom/dispose!)
                                     (when orig-will-unmount (orig-will-unmount))))]
          ;(log/info "Is component instance, will unmount: " (js-keys ?app) (.-componentWillUnmount ?app))
          (set! (.-componentWillUnmount component) new-unmount)
          (if (nil? reactive-atom)
            (let [out (ratom/run-in-reaction
                        #(subs/subscribe get-input-db get-handler cache-lookup get-subscription-cache app query)
                        component "cljsRatom"
                        ;; thinking over rendering strategies
                        ;; I think just setting blindly-render to true and then calling (refresh-component component)
                        ;; is probably easiest to avoid dealing with too many internal fulcro details or gotchas
                        ;; with state getting out of sync.
                        ;; so it seems like none of the options actually re-render the component except for forceUpdate..
                        ;; even with blindly-render set to true
                        ;(log/info "REACTIVE RENDERING OF COMPONENT: " component)
                        ;(log/info "ident: " (c/get-ident component))
                        ;(when-not (c/get-ident component) (def c' component))
                        ;(comment (c/get-ident c') (c/props c') )
                        ;(log/info "ident: " (c/get-ident component))
                        ;(log/info "blindly render is: " c/*blindly-render*)
                        (fn [component]
                          ;(when (count components-to-refresh))
                          ;(run! )
                          ;; for each component call forceupdaet
                          ;()
                          (.log js/console "Pushing component for refresh")
                          ;(add-component-to-refresh! app component)
                          ;(.push components-to-refresh component)
                          (js/requestAnimationFrame (fn []
                                                      (.log js/console "IN RAF, refresh component: " component)
                                                      (.log js/console "IN RAF, refresh component: " (c/get-ident component))
                                                      ;(binding [c/*blindly-render* false] (c/refresh-component! component))
                                                      (binding [c/*blindly-render* true] (c/refresh-component! component))
                                                      ;#_(let [components (get-components-to-refresh app)]
                                                      ;  (when (> (.-length components) 0)
                                                      ;    (.log js/console "component to refresh: " components)
                                                      ;    (.log js/console "RUNNING COMP forceUpdate")
                                                      ;
                                                      ;    (run! #(binding [c/*blindly-render* true] (c/refresh-component! %)) components)
                                                      ;    ;(run! #(.forceUpdate %) components)
                                                      ;    (set! (.-length components) 0)))
                                                      )))

                        ;(c/tunnel-props! component (update (c/props component) ::counter inc))
                        ;(fulcro.render/render-component! app (c/get-ident component) component)

                        reaction-opts)
                  ]
              (log/info "OUTPUT: " out) out)
            (do
              (log/info "in second branch")
              ;(._run reactive-atom false)
              (subs/subscribe get-input-db get-handler cache-lookup get-subscription-cache app query))))
        (do
          (log/info "only have an app")
          (subs/subscribe get-input-db get-handler cache-lookup get-subscription-cache (c/any->app ?app) query))))))

(defn <sub [app query]
  (let [value (subscribe app query)]
    (log/info "<sub value: " value)
    (.log js/console "<sub value: " value)
    (when value
      @value)))

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
  (clear-subscription-cache! registry))
