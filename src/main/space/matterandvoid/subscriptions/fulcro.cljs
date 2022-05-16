(ns space.matterandvoid.subscriptions.fulcro
  (:require-macros [space.matterandvoid.subscriptions.fulcro])
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ident-optimized-render]
    [space.matterandvoid.subscriptions.impl.fulcro :as impl]
    [space.matterandvoid.subscriptions.impl.loggers :refer [console]]
    [reagent.ratom]
    [taoensso.timbre :as log]))

(defn set-memoize! [f] (impl/set-memoize! f))

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
  [query-id & args]
  (apply impl/reg-sub query-id args))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. Also known as a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced"
  [?app query] (impl/subscribe ?app query))

(defn <sub
  "Subscribe and deref a subscription, returning its value, not a reaction."
  [?app query] (impl/<sub ?app query))

(defn clear-sub
  "Unregisters subscription handlers (presumably registered previously via the use of `reg-sub`).

  When called with no args, it will unregister all currently registered subscription handlers.

  When given one arg, assumed to be the `id` of a previously registered
  subscription handler, it will unregister the associated handler. Will produce a warning to
  console if it finds no matching registration.

  NOTE: Depending on the usecase, it may be necessary to call `clear-subscription-cache!` afterwards"
  {:api-docs/heading "Subscriptions"}
  ([registry] (impl/clear-handlers registry))
  ([registry query-id] (impl/clear-handlers registry query-id)))

(defn reg-sub-raw
  "This is a low level, advanced function.  You should probably be
  using `reg-sub` instead.

  Some explanation is available in the docs at
  <a href=\"http://day8.github.io/re-frame/flow-mechanics/\" target=\"_blank\">http://day8.github.io/re-frame/flow-mechanics/</a>"
  {:api-docs/heading "Subscriptions"}
  [query-id handler-fn] (impl/register-handler! query-id handler-fn))

(defn clear-subscription-cache!
  "Removes all subscriptions from the cache.

  This function can be used at development time or test time. Useful when hot reloading
  namespaces containing subscription handlers. Also call it after a React/render exception,
  because React components won't have been cleaned up properly. And this, in turn, means
  the subscriptions within those components won't have been cleaned up correctly. So this
  forces the issue."
  [registry] (impl/clear-subscription-cache! registry))

;; component rendering integrations

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reactive refresh of components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; todo this only supports class components right now, not hooks.
;; how about: `(use-subs [::sub1 {:args 1}] [::sub2 {:args 2}])`

(defn cleanup! "Intended to be called when a component unmounts to clear the registered Reaction."
  [this] (impl/cleanup! this))

(defn setup-reaction!
  "Installs a Reaction on the provided component which will re-render the component when any of the subscriptions'
   values change.
   Takes a component instance and a render function with signature: (fn render [this])"
  [this client-render] (impl/setup-reaction! this client-render))

(defn fulcro-app
  "Proxies to com.fulcrologic.fulcro.application/fulcro-app
   and then assoc'es a reagent.ratom/atom for the fulcro state-atom with :initial-db if present in the args map.
   Also uses ident-optimized-render/render! as the rendering algorithm if no :optimized-render! is passed in args."
  [args]
  {:pre [(map? args)]}
  (let [args (cond->
               (-> args
                 (assoc :render-middleware
                        (fn [this render-fn]
                          (let [client-middleware (or (:render-middleware args) (fn [_this f] (f)))]
                            (log/info "IN render-middleware")
                            (if-let [^clj reaction (impl/get-component-reaction this)]
                              (._run reaction false)
                              (setup-reaction! this (fn [] (client-middleware this render-fn)))))))
                 ;(assoc :component-will-unmount-middleware
                 ;       (fn [this cwu]
                 ;         (let [client-cwu (or (:component-will-unmount-middleware args) (fn [_this f] (f)))]
                 ;           (log/info "comp will unmount CLEANING UP" (c/component-name this))
                 ;           (cleanup! this)
                 ;           (client-cwu this cwu))))
                 )
               (not (:optimized-render! args))
               (assoc :optimized-render! ident-optimized-render/render!))]
    (assoc (fulcro.app/fulcro-app args)
      ::fulcro.app/state-atom (reagent.ratom/atom (:initial-db args {})))))
