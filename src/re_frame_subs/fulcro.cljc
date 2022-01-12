(ns re-frame-subs.fulcro
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [re-frame-subs.loggers :refer [console]]
    [re-frame-subs.subs :as subs]))

(defn get-input-db [app] (if app (fulcro.app/current-state app) (console :info "APP IS NULL")))

(defn get-input-db-signal
  "Given the storage for the subscriptions return an atom containing a map
  (this it the 'db' in re-frame parlance)."
  [app] (::fulcro.app/state-atom app))

;; for other proxy interfaces (other than fulcro storage) this has to be an atom of a map.
;; this is here for now just to inspect it at the repl
(defonce subs-cache (atom {}))
(defn get-subscription-cache [app] subs-cache #_(atom {}))
(defn cache-lookup [app query-v]
  (if app
    (get @(get-subscription-cache app) [query-v])))

(def debug-enabled? false)

(def subs-key ::subs)

(defn get-handler
  "Returns a \"handler\" function registered for the subscription with the given `id`.
  Fulcro app and 'query-id' -> subscription handler function.
  Lookup in the place where the query-id -> handler functions are stored."
  ([app id]
   (get-in @(::fulcro.app/runtime-atom app) [subs-key id]))

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
  (swap! (::fulcro.app/runtime-atom app) assoc-in [subs-key id] handler-fn)
  handler-fn)

(defn clear-handlers
  ;; clear all handlers
  ([db] (assoc db subs-key {}))
  ([db id]
   (if (get-handler db id)
     (update db subs-key dissoc id)
     (console :warn "Subscriptions: can't clear handler for" (str id ". Handler not found.")))))

;; -- subscriptions -----------------------------------------------------------

(defn reg-sub
  "A call to `reg-sub` associates a `query-id` with two functions.

  The two functions provide 'a mechanism' for creating a node
  in the Signal Graph. When a node of type `query-id` is needed,
  the two functions can be used to create it.

  The three arguments are:

  - `query-id` - typically a namespaced keyword (later used in subscribe)
  - optionally, an `input signals` function which returns the input data
    flows required by this kind of node.
  - a `computation function` which computes the value (output) of the
    node (from the input data flows)

  Later, during app execution, a call to `(subscribe [:sub-id 3 :blue])`,
  will trigger the need for a new `:sub-id` Signal Graph node (matching the
  query `[:sub-id 3 :blue]`). And, to create that node the two functions
  associated with `:sub-id` will be looked up and used.

  Just to be clear: calling `reg-sub` does not immediately create a node.
  It only registers 'a mechanism' (the two functions) by which nodes
  can be created later, when a node is bought into existence by the
  use of `subscribe` in a `View Function`.

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
  `computation function` (as the 1st argument) when it is called.

  So, `reg-sub` can be called in one of three ways, because there are three ways
  to define the input signals part. But note, the 2nd method, in which a
  `signals function` is explicitly supplied, is the most canonical and
  instructive. The other two are really just sugary variations.

  **First variation** - no input signal function given:

      #!clj
      (reg-sub
        :query-id
        a-computation-fn)   ;; has signature:  (fn [db query-vec]  ... ret-value)

     In the absence of an explicit `signals function`, the node's input signal defaults to `app-db`
     and, as a result, the value within `app-db` (a map) is
     given as the 1st argument when `a-computation-fn` is called.


  **Second variation** - a signal function is explicitly supplied:

      #!clj
      (reg-sub
        :query-id
        signal-fn     ;; <-- here
        computation-fn)

  This is the most canonical and instructive of the three variations.

  When a node is created from the template, the `signal function` will be called and it
  is expected to return the input signal(s) as either a singleton, if there is only
  one, or a sequence if there are many, or a map with the signals as the values.

  The current values of the returned signals will be supplied as the 1st argument to
  the `a-computation-fn` when it is called - and subject to what this `signal-fn` returns,
  this value will be either a singleton, sequence or map of them (paralleling
  the structure returned by the `signal function`).

  This example `signal function` returns a 2-vector of input signals.

      #!clj
      (fn [query-vec dynamic-vec]
         [(subscribe [:a-sub])
          (subscribe [:b-sub])])

  The associated computation function must be written
  to expect a 2-vector of values for its first argument:

      #!clj
      (fn [[a b] query-vec]     ;; 1st argument is a seq of two values
        ....)

  If, on the other hand, the signal function was simpler and returned a singleton, like this:

      #!clj
      (fn [query-vec dynamic-vec]
        (subscribe [:a-sub]))      ;; <-- returning a singleton

  then the associated computation function must be written to expect a single value
  as the 1st argument:

      #!clj
      (fn [a query-vec]       ;; 1st argument is a single value
         ...)

  Further Note: variation #1 above, in which an `input-fn` was not supplied, like this:

      #!clj
      (reg-sub
        :query-id
        a-computation-fn)   ;; has signature:  (fn [db query-vec]  ... ret-value)

  is the equivalent of using this
  2nd variation and explicitly supplying a `signal-fn` which returns `app-db`:

      #!clj
      (reg-sub
        :query-id
        (fn [_ _]  re-frame/app-db)   ;; <--- explicit signal-fn
        a-computation-fn)             ;; has signature:  (fn [db query-vec]  ... ret-value)

  **Third variation** - syntax Sugar

      #!clj
      (reg-sub
        :a-b-sub
        :<- [:a-sub]
        :<- [:b-sub]
        (fn [[a b] query-vec]    ;; 1st argument is a seq of two values
          {:a a :b b}))

  This 3rd variation is just syntactic sugar for the 2nd.  Instead of providing an
  `signals-fn` you provide one or more pairs of `:<-` and a subscription vector.

  If you supply only one pair a singleton will be supplied to the computation function,
  as if you had supplied a `signal-fn` returning only a single value:

      #!clj
      (reg-sub
        :a-sub
        :<- [:a-sub]
        (fn [a query-vec]      ;; only one pair, so 1st argument is a single value
          ...))

  For further understanding, read the tutorials, and look at the detailed comments in
  /examples/todomvc/src/subs.cljs.

  See also: `subscribe`"
  [app_ query-id & args]
  ;; In some fulcro apps, the application is set asynchronously on boot of the application, this lets us capture its
  ;; current value - requires passing a Var as app though.
  #?(:cljs
     (js/setTimeout
       (fn []
         (let [app (if (var? app_) @app_ app_)]
           (assert app)
           (apply subs/reg-sub
             get-input-db get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
             app query-id args))))))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. So, in FRP-ish terms,
  it returns a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced:

      #!clj
      (let [signal (subscribe [:items])
            value  (deref signal)]     ;; could be written as @signal
        ...)

   which is typically written tersely as simple:

      #!clj
      (let [items  @(subscribe [:items])]
        ...)


  `query` is a vector of at least one element. The first element is the
  `query-id`, typically a namespaced keyword. The rest of the vector's
  elements are optional, additional values which parameterise the query
  performed.

  `dynv` is an optional 3rd argument, which is a vector of further input
  signals (atoms, reactions, etc), NOT values. This argument exists for
  historical reasons and is borderline deprecated these days.

  **Example Usage**:

      #!clj
      (subscribe [:items])
      (subscribe [:items \"blue\" :small])
      (subscribe [:items {:colour \"blue\"  :size :small}])

  Note: for any given call to `subscribe` there must have been a previous call
  to `reg-sub`, registering the query handler (functions) associated with
  `query-id`.

  **Hint**

  When used in a view function BE SURE to `deref` the returned value.
  In fact, to avoid any mistakes, some prefer to define:

      #!clj
      (def <sub  (comp deref re-frame.core/subscribe))

  And then, within their views, they call  `(<sub [:items :small])` rather
  than using `subscribe` directly.

  **De-duplication**

  Two, or more, concurrent subscriptions for the same query will
  source reactive updates from the one executing handler.
::habits
  See also: `reg-sub`
  "
  [app query]
  (subs/subscribe get-input-db get-handler cache-lookup get-subscription-cache
    app query))

(defn <sub
  [app query]
  @(subs/subscribe get-input-db get-handler cache-lookup get-subscription-cache app query))

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
