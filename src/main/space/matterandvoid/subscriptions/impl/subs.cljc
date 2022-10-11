(ns space.matterandvoid.subscriptions.impl.subs
  #?(:cljs (:require-macros [space.matterandvoid.subscriptions.impl.subs]))
  (:require
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.impl.loggers :refer [console]]
    [space.matterandvoid.subscriptions.impl.trace :as trace :include-macros true]
    [taoensso.timbre :as log]))

(defn- error [& args] #?(:cljs (js/Error. (apply str args)) :clj (Exception. ^String (apply str args))))

;; -- cache -------------------------------------------------------------------

(defn clear-subscription-cache!
  "calls `on-dispose` for each cached item,
   which will cause the value to be removed from the cache"
  [get-subscription-cache app]
  (console :info "Clearing subscription cache")
  (doseq [[_ rxn] @(get-subscription-cache app)] (ratom/dispose! rxn))
  (if (not-empty @(get-subscription-cache app))
    (console :warn "subscriptions: The subscription cache isn't empty after being cleared")))

(defn cache-and-return!
  "cache the reaction r"
  [get-subscription-cache get-cache-key app query-v #?(:cljs ^clj reaction-or-cursor :clj reaction-or-cursor)]
  ;; this prevents memory leaks (caching subscription -> reaction) but still allows
  ;; executing outside of a (reagent.reaction) form, like in event handlers.
  (when (ratom/reactive-context?)
    (let [cache-key          (get-cache-key app query-v)
          subscription-cache (get-subscription-cache app)
          on-dispose         (fn [] (trace/with-trace {:operation (first query-v)
                                                       :op-type   :sub/dispose
                                                       :tags      {:query-v  query-v
                                                                   :reaction (ratom/reagent-id reaction-or-cursor)}}
                                      (swap! subscription-cache
                                        (fn [query-cache]
                                          (if (and (contains? query-cache cache-key) (identical? reaction-or-cursor (get query-cache cache-key)))
                                            (dissoc query-cache cache-key)
                                            query-cache)))))]
      ;(log/info "CACHING REACTION with KEY: " cache-key)
      ;; when this reaction is no longer being used, remove it from the cache

      (when (ratom/reaction? reaction-or-cursor) (ratom/add-on-dispose! reaction-or-cursor on-dispose))
      (when (ratom/cursor? reaction-or-cursor) (set! (.-on-dispose reaction-or-cursor) on-dispose))
      (swap! subscription-cache (fn [query-cache]
                                  (when ratom/debug-enabled?
                                    (when (contains? query-cache cache-key)
                                      (console :warn "subscriptions: Adding a new subscription to the cache while there is an existing subscription in the cache" cache-key)))
                                  (assoc query-cache cache-key reaction-or-cursor)))
      (trace/merge-trace! {:tags {:reaction (ratom/reagent-id reaction-or-cursor)}})))
  reaction-or-cursor)

;; -- subscribe ---------------------------------------------------------------

(defn subscribe
  "Takes a datasource and query and returns a Reaction."
  [get-handler cache-lookup get-subscription-cache get-cache-key
   datasource query]
  ;(log/debug "\n\nSUBSCRIBE IMPL--------------------------------------------")
  ;(log/debug "subscribe query: " query)
  (assert (vector? query) (str "Queries must be vectors, you passed: " query))
  (let [cnt       (count query),
        query-id  (first query)
        cache-key (get-cache-key datasource query)]
    (assert (or (= 1 cnt) (= 2 cnt)) (str "Query must contain only one map for subscription " query-id))
    (when (and (= 2 cnt) (not (or (nil? (get query 1)) (map? (get query 1)))))
      (throw (error "Args to the query vector must be one map for subscription " query-id "\n" "Received query: " (pr-str query))))
    (trace/with-trace {:operation (first query)
                       :op-type   :sub/create
                       :tags      {:query-v query}}
      (let [cached-reaction (cache-lookup datasource cache-key)]
        (if cached-reaction
          (do (trace/merge-trace! {:tags {:cached? true :reaction (ratom/reagent-id cached-reaction)}})
              cached-reaction)
          (let [handler-fn (get-handler query-id)]
            (assert (fn? handler-fn) (str "Subscription handler for the following query is missing\n\n" (pr-str query-id) "\n"))
            (trace/merge-trace! {:tags {:cached? false}})
            (if (nil? handler-fn)
              (do (trace/merge-trace! {:error true})
                  (console :error (str "No subscription handler registered for: " query-id "\n\nReturning a nil subscription.")))
              (let [handler-args (second query)]
                (assert (or (nil? handler-args) (map? handler-args)))
                (let [reaction
                      #?(:cljs (handler-fn datasource handler-args)
                         :clj (try (handler-fn datasource handler-args) (catch clojure.lang.ArityException _ (handler-fn datasource))))]
                  (cache-and-return! get-subscription-cache get-cache-key datasource query reaction))))))))))

;; -- reg-sub -----------------------------------------------------------------

(defn map-vals
  "Returns a new version of 'm' in which 'f' has been applied to each value.
  (map-vals inc {:a 4, :b 2}) => {:a 5, :b 3}"
  [f m]
  (into {} (map (fn [[k v]]
                  [k (if (sequential? v) (mapv f v) (f v))])) m))

(defn map-signals
  "Runs f over signals. Signals may take several
  forms, this function handles all of them."
  [f signals]
  (cond
    (sequential? signals) (map f signals)
    (map? signals) (map-vals f signals)
    (ratom/deref? signals) (f signals)
    :else '()))

(def valid-signals (some-fn sequential? map? ratom/deref?))

(defn deref-input-signals
  [signals query-id]
  (when-not (valid-signals signals)
    (let [to-seq #(cond-> % (not (sequential? %)) list)]
      (console :error "space.matterandvoid.subscriptions: in the reg-sub for" query-id ", the input-signals function returns:" signals)
      ;(trace/merge-trace! {:tags {:input-signals (doall (to-seq (map-signals reagent-id signals)))}})
      ))
  (map-signals deref signals))

(defn make-subs-handler-fn
  "A subscription is just a function that returns a reagent.Reaction.

  This is where the inputs-fn is executed and the computation is put inside a reaction - ie a callback for later
  invocation when subscribe is called and derefed."
  [inputs-fn computation-fn query-id]
  (fn subs-handler-fn
    [app args]
    (assert (or (nil? args) (map? args)) (str "Args must be a map" args))
    (let [subscriptions #?(:cljs (inputs-fn app args)
                           :clj (try (inputs-fn app args) (catch clojure.lang.ArityException _ (inputs-fn app))))
          reaction-id           (atom nil)
          reaction              (ratom/make-reaction
                                  (fn []
                                    (trace/with-trace {:operation query-id
                                                       :op-type   :sub/run
                                                       :tags      {:query-v  [query-id args]
                                                                   :reaction @reaction-id}}

                                      #?(:cljs
                                         (let [subscription-output (computation-fn (deref-input-signals subscriptions query-id) args)]
                                           (trace/merge-trace! {:tags {:value subscription-output}})
                                           subscription-output)
                                         ;; Deal with less leniency on jvm for calling single-arity functions with 2 args
                                         :clj (try
                                                (let [subscription-output (computation-fn (deref-input-signals subscriptions query-id) args)]
                                                  (trace/merge-trace! {:tags {:value subscription-output}})
                                                  subscription-output)
                                                (catch clojure.lang.ArityException _
                                                  (computation-fn (deref-input-signals subscriptions query-id))))))))]
      (reset! reaction-id (ratom/reagent-id reaction))
      reaction)))

(def memoize-fn identity)
(def args-merge-fn merge)

(defn set-memoize-fn! [f] #?(:cljs (set! memoize-fn f)
                             :clj  (alter-var-root #'memoize-fn (fn [_] f))))
(defn set-args-merge-fn! [f] #?(:cljs (set! args-merge-fn f)
                                :clj  (alter-var-root #'args-merge-fn (fn [_] f))))

(defn merge-update-args [subs-vec args*] (cond-> subs-vec (map? args*) (update 1 args-merge-fn args*)))

(defn args->inputs-fn [get-input-db-signal subscribe err-header
                       args]
  (case (count args)
    ;; no `inputs` function provided - give the default
    0
    (do
      (fn
        ([app]
         (let [start-signal (get-input-db-signal app)]
           #?(:cljs (when goog/DEBUG
                      (when-not (ratom/ratom? start-signal)
                        (throw (error "Your input signal must be a reagent.ratom. You provided: " (pr-str start-signal))))))
           start-signal))
        ([app _]
         (let [start-signal (get-input-db-signal app)]
           #?(:cljs (when goog/DEBUG
                      (when-not (ratom/ratom? start-signal)
                        (throw (error "Your input signal must be a reagent.ratom. You provided: " (pr-str start-signal))))))
           start-signal))))

    ;; a single `inputs` fn
    1 (let [f (first args)]
        (when-not (fn? f)
          (console :error err-header "2nd argument expected to be an inputs function, got:" f))
        f)

    ;; one :<- pair
    2 (let [[marker signal-vec] args]
        (when-not (= :<- marker)
          (console :error err-header "expected :<-, got:" marker))
        (fn inputs-fn-
          ([app] (subscribe app signal-vec))
          ([app args*] (subscribe app (merge-update-args signal-vec args*)))))

    ;; multiple :<- pairs
    (let [pairs   (partition 2 args)
          markers (map first pairs)
          vecs    (map second pairs)]
      (when-not (and (every? #{:<-} markers) (every? vector? vecs))
        (console :error err-header "expected pairs of :<- and vectors, got:" pairs))

      (fn inputs-fn
        ([app] (map #(subscribe app %) vecs))
        ([app args*]
         (map #(subscribe app (merge-update-args % args*))
           vecs))))))

(defn parse-reg-sub-args [get-input-db-signal subscribe err-header args]
  (let [[input-args ;; may be empty, or one signal fn, or pairs of  :<- / vector
         computation-fn] (let [[op f :as comp-f] (take-last 2 args)]
                           (if (or (= 1 (count comp-f))
                                 (fn? op)
                                 (vector? op))
                             [(butlast args) (last args)]
                             (let [args (drop-last 2 args)]
                               (case op
                                 ;; return a function that calls the computation fn
                                 ;;  on the input signal, removing the query vector
                                 :-> [args (fn compute-fn [db] (f db))]

                                 ;; an incorrect keyword was passed
                                 (console :error err-header "expected :-> as second to last argument, got:" op)))))]
    [(args->inputs-fn get-input-db-signal subscribe err-header input-args) computation-fn]))

(defn reg-sub
  "db, fully qualified keyword for the query id
  optional positional args."
  [get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup get-cache-key
   query-id & args]
  (let [err-header (str "space.matterandvoid.subscriptions: reg-sub for " query-id ", ")
        subscribe' (partial subscribe get-handler cache-lookup get-subscription-cache get-cache-key)
        [inputs-fn compute-fn] (parse-reg-sub-args get-input-db-signal subscribe' err-header args)
        _          (assert (ifn? compute-fn) "Last arg should be a function (your computation function).")]
    (register-handler! query-id (make-subs-handler-fn inputs-fn compute-fn query-id))))

(defn reg-layer2-sub
  [get-input-db-signal register-handler!
   query-id path-vec-or-fn]
  (register-handler! query-id
    (fn layer2-handler-fn
      ([app]
       (let [db-ratom (get-input-db-signal app)
             path     (if (fn? path-vec-or-fn) (path-vec-or-fn) path-vec-or-fn)]
         (ratom/cursor db-ratom path)))
      ([app args]
       (assert (or (nil? args) (map? args)))
       (let [db-ratom (get-input-db-signal app)
             path     (if (fn? path-vec-or-fn) (path-vec-or-fn args) path-vec-or-fn)]
         (ratom/cursor db-ratom path))))))

(defn reg-sub-raw [register-handler! query-id handler-fn]
  (register-handler! query-id
    (fn
      ([db_] (handler-fn db_))
      ([db_ args] (handler-fn db_ args)))))

(defn sub-fn
  "Takes a function that returns either a Reaction or RCursor. Returns a function that when invoked delegates to `f` and
   derefs its output. The returned function can be used in subscriptions."
  [meta-sub-kw f]
  (with-meta
    (fn
      ([] (deref (f)))
      ([datasource] (deref (f datasource)))
      ([datasource args] (deref (f datasource args))))
    {meta-sub-kw f}))

#?(:clj
   (defmacro deflayer2-sub
     "Only supports use cases where your datasource is a hashmap.

     Takes a symbol for a subscription name and a way to derive a path in your datasource hashmap. Returns a function subscription
     which itself returns a Reagent RCursor.
     Supports a vector path, a single keyword, or a function which takes the arguments map passed to subscribe and
     must return a path vector to use as an RCursor path.

     Examples:

     (deflayer2-sub my-subscription :a-path-in-your-db)

     (deflayer2-sub my-subscription [:a-path-in-your-db])

     (deflayer2-sub my-subscription (fn [sub-args-map] [:a-key (:some-val sub-args-map])))
     "
     [meta-sub-kw get-input-db-signal sub-name ?path]
     (let [args-sym (gensym "args")
           path-sym (gensym "path")]
       `(let [subscription-fn#

              (fn ~sub-name
                ([datasource#]
                 (let [db-ratom# (~get-input-db-signal datasource#)
                       ~path-sym ~(cond
                                    (keyword? ?path) [?path]
                                    (vector? ?path) ?path
                                    :else `(if (fn? ~?path) (~?path) ~?path))]
                   ~(when (:ns &env)
                      `(when goog/DEBUG (assert (vector? ~path-sym) (str "Layer 2 subscription \"" '~(symbol (str (:name (:ns &env))) (name sub-name)) "\" must return a vector path."))))
                   (ratom/cursor db-ratom# ~path-sym)))

                ([datasource# ~args-sym]
                 (assert (or (nil? ~args-sym) (map? ~args-sym)))
                 (let [db-ratom# (~get-input-db-signal datasource#)
                       ~path-sym ~(cond
                                    (keyword? ?path) [?path]
                                    (vector? ?path) ?path
                                    :else `(if (fn? ~?path) (~?path ~args-sym) ~?path))]
                   ~(when (:ns &env)
                      `(when goog/DEBUG (assert (vector? ~path-sym) (str "Layer 2 subscription \"" '~(symbol (str (:name (:ns &env))) (name sub-name)) "\" must return a vector path."))))
                   (ratom/cursor db-ratom# ~path-sym))))]

          (def ~sub-name (sub-fn ~meta-sub-kw subscription-fn#))))))
