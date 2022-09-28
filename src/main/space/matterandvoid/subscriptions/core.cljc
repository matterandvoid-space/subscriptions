(ns space.matterandvoid.subscriptions.core
  #?(:cljs (:require-macros [space.matterandvoid.subscriptions.core]))
  (:require
    [space.matterandvoid.subscriptions.impl.core :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defn set-memoize-fn! [f] (impl/set-memoize-fn! f))
(defn set-args-merge-fn! [f] (impl/set-args-merge-fn! f))

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

(defn reg-layer2-sub
  [query-id path-vec-or-fn]
  (impl/reg-layer2-sub query-id path-vec-or-fn))

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
  [query-id handler-fn] (impl/reg-sub-raw query-id handler-fn))

(defn clear-subscription-cache!
  "Removes all subscriptions from the cache.

  This function can be used at development time or test time. Useful when hot reloading
  namespaces containing subscription handlers. Also call it after a React/render exception,
  because React components won't have been cleaned up properly. And this, in turn, means
  the subscriptions within those components won't have been cleaned up correctly. So this
  forces the issue."
  [registry] (impl/clear-subscription-cache! registry))

#?(:clj
   (defmacro defregsub
     "Has the same function signature as `reg-sub`.
     Registers a subscription and creates a function which invokes subscribe and deref on the registered subscription
     with the args map passed in."
     [sub-name & args]
     (let [sub-kw (keyword (str *ns*) (str sub-name))]
       `(do
          (reg-sub ~sub-kw ~@args)

          (defn ~sub-name
            ([app#] (deref (subscribe app# [~sub-kw])))
            ([app# args#] (deref (subscribe app# [~sub-kw args#]))))))))

#?(:clj
   (defmacro defsub
     "Has the same function signature as `reg-sub`.
     Returns a subscription function and creates a function which invokes subscribe and deref on the registered subscription
     with the args map passed in."
     [fn-name & args]
     (let [compute-fn' (gensym "compute-fn")
           inputs'     (gensym "inputs")
           sub-args'   (gensym "sub-args")]
       `(let [[inputs-fn# ~compute-fn'] (impl/parse-reg-sub-args ~(vec args))]
          (defn ~fn-name

            ([datasource#]
             (let [input-subscriptions# (inputs-fn# datasource#)]
               (ratom/make-reaction
                 (fn [] (~compute-fn' (impl/deref-input-signals input-subscriptions# (str *ns* "/" '~fn-name)))))))

            ([datasource# ~sub-args']
             (let [input-subscriptions# (inputs-fn# datasource# ~sub-args')]
               (ratom/make-reaction
                 (fn [] (let [~inputs' (impl/deref-input-signals input-subscriptions# (str *ns* "/" '~fn-name))]
                          ~(if (:ns &env)
                             `(~compute-fn' ~inputs' ~sub-args')
                             `(try (~compute-fn' ~inputs' ~sub-args')
                                   (catch clojure.lang.ArityException ~'_
                                     (~compute-fn' ~inputs'))))))))))))))
