(ns re-frame-subs.subs
  (:require
    [re-frame-subs.interop :refer [add-on-dispose! debug-enabled? make-reaction ratom? deref? dispose! reagent-id]]
    [re-frame-subs.loggers :refer [console]]
    [com.fulcrologic.fulcro.application :as f.app]
    [re-frame-subs.utils :refer [first-in-vector]]
    [re-frame-subs.trace :as trace :include-macros true]
    [com.fulcrologic.fulcro.application :as app]))

(defn state-atom [app] (::f.app/state-atom app ))

(defn get-input-db [app] (f.app/current-state app))
(defn get-input-db-signal [app] (state-atom app))

(defn get-subscription-cache [app]
  (atom {}))

(def subs-key ::subs)

(defn get-handler
  "Fulcro app -> subscription handler function."
  ([app id]
   (get-in (f.app/current-state app) [subs-key id]))

  ([app id required?]
   (let [handler (get-handler app id)]
     (when debug-enabled? ;; This is in a separate `when` so Closure DCE can run ...
       (when (and required? (nil? handler)) ;; ...otherwise you'd need to type-hint the `and` with a ^boolean for DCE.
         (console :error "Subscription: no handler registered for:" id)))
     handler)))

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [app id handler-fn]
  (.log js/console "REGISTERING HANDLER for id: " id, " app: " app)
  (swap! (state-atom app) assoc-in [subs-key id] handler-fn)
  handler-fn)

(defn clear-handlers
  ;; clear all handlers
  ([db] (assoc db subs-key {}))
  ([db id]
   (if (get-handler db id)
     (update db subs-key dissoc id)
     (console :warn "Subscriptions: can't clear handler for" (str id ". Handler not found.")))))

;; -- cache -------------------------------------------------------------------
;;
;; De-duplicate subscriptions. If two or more equal subscriptions
;; are concurrently active, we want only one handler running.
;; Two subscriptions are "equal" if their query vectors test "=".

(defn clear-subscription-cache!
  "calls `on-dispose` for each cached item,
   which will cause the value to be removed from the cache"
  [app]
  (doseq [[_ rxn] @(get-subscription-cache app)] (dispose! rxn))
  (if (not-empty @(get-subscription-cache app))
    (console :warn "re-frame: The subscription cache isn't empty after being cleared")))

(defn cache-and-return!
  "cache the reaction r"
  [app query-v reaction]
  (let [cache-key query-v
        subscription-cache (get-subscription-cache app)]
    (.log js/console "CACHING! subs cache: " subscription-cache)
    ;; when this reaction is no longer being used, remove it from the cache
    (add-on-dispose! reaction #(trace/with-trace {:operation (first-in-vector query-v)
                                                  :op-type   :sub/dispose
                                                  :tags      {:query-v  query-v
                                                              :reaction (reagent-id reaction)}}
                                 (swap! subscription-cache
                                   (fn [query-cache]
                                     (if (and (contains? query-cache cache-key) (identical? reaction (get query-cache cache-key)))
                                       (dissoc query-cache cache-key)
                                       query-cache)))))
    ;; cache this reaction, so it can be used to deduplicate other, later "=" subscriptions
    (swap! subscription-cache (fn [query-cache]
                             (when debug-enabled?
                               (when (contains? query-cache cache-key)
                                 (console :warn "re-frame: Adding a new subscription to the cache while there is an existing subscription in the cache" cache-key)))
                             (assoc query-cache cache-key reaction)))
    (trace/merge-trace! {:tags {:reaction (reagent-id reaction)}})
    reaction)) ;; return the actual reaction

(defn cache-lookup
  [app query-v]
  (get @(get-subscription-cache app) [query-v]))

;; -- subscribe ---------------------------------------------------------------

(defn subscribe
  [app query]
  (let [input-db (get-input-db app)]
    (trace/with-trace {:operation (first-in-vector query)
                       :op-type   :sub/create
                       :tags      {:query-v query}}
      (if-let [cached (cache-lookup app query)]
        (do
          (trace/merge-trace! {:tags {:cached?  true
                                      :reaction (reagent-id cached)}})
          (.log js/console "HAVE CACHED, returning")
          cached)

        (let [query-id   (first-in-vector query)
              handler-fn (get-handler app query-id)]
          (.log js/console "DO NOT HAVE CACHED")
          (assert handler-fn "Handler for query missing")

          (trace/merge-trace! {:tags {:cached? false}})
          (if (nil? handler-fn)
            (do (trace/merge-trace! {:error true})
                (console :error (str "re-frame: no subscription handler registered for: " query-id ". Returning a nil subscription.")))
            (do (.log js/console "HAve handler invoking")
             (cache-and-return! app query (handler-fn input-db query)))))))))

;; -- reg-sub -----------------------------------------------------------------

(defn- map-vals
  "Returns a new version of 'm' in which 'f' has been applied to each value.
  (map-vals inc {:a 4, :b 2}) => {:a 5, :b 3}"
  [f m]
  (into (empty m)
    (map (fn [[k v]] [k (f v)]))
    m))

(defn map-signals
  "Runs f over signals. Signals may take several
  forms, this function handles all of them."
  [f signals]
  (cond
    (sequential? signals) (map f signals)
    (map? signals) (map-vals f signals)
    (deref? signals) (f signals)
    :else '()))

(defn to-seq
  "Coerces x to a seq if it isn't one already"
  [x]
  (cond-> x (not (sequential? x)) list))

(defn- deref-input-signals
  [signals query-id]
  (.log js/console "deref-input-signals: " query-id ", " signals)
  (let [dereffed-signals (map-signals deref signals)]
    (cond
      (sequential? signals) (map deref signals)
      (map? signals) (map-vals deref signals)
      (deref? signals) (deref signals)
      :else (console :error "re-frame: in the reg-sub for" query-id ", the input-signals function returns:" signals))
    (trace/merge-trace! {:tags {:input-signals (doall (to-seq (map-signals reagent-id signals)))}})
    dereffed-signals))

(defn make-subs-handler-fn
  [inputs-fn computation-fn query-id]
  (fn subs-handler-fn
    [_db query-vec]
    (let [subscriptions (inputs-fn query-vec nil)
          reaction-id   (atom nil)
          _ (.log js/console "IN SUBS HANDLER 1")
          reaction      (make-reaction
                          (fn []
                            (trace/with-trace {:operation (first-in-vector query-vec)
                                               :op-type   :sub/run
                                               :tags      {:query-v  query-vec
                                                           :reaction @reaction-id}}
                              (.log js/console "IN the reaction callback")
                              (let [subscription-output (computation-fn (deref-input-signals subscriptions query-id) query-vec)]
                                (.log js/console "IN the reaction callback 2 sub output: " subscription-output)
                                (trace/merge-trace! {:tags {:value subscription-output}})
                                subscription-output))))]

      _ (.log js/console "IN SUBS HANDLER 2")
      (reset! reaction-id (reagent-id reaction))
      reaction)))

(defn reg-sub
  "db, fully qualified keyword for the query id
  optional positional args: "
  [app query-id & args]
  (let [computation-fn (last args)
        _              (assert (fn? computation-fn) "Last arg should be function - your computation function.")
        input-args     (butlast args) ;; may be empty, or one signal fn, or pairs of  :<- / vector
        err-header     (str "re-frame: reg-sub for " query-id ", ")
        input-db       (get-input-db app)
        input-db-signal       (get-input-db-signal app)
        inputs-fn      (case (count input-args)
                         ;; no `inputs` function provided - give the default
                         0 (fn
                             ([_] input-db-signal)
                             ([_ _] input-db-signal))

                         ;; a single `inputs` fn
                         1 (let [f (first input-args)]
                             (when-not (fn? f)
                               (console :error err-header "2nd argument expected to be an inputs function, got:" f))
                             f)

                         ;; one sugar pair
                         2 (let [[marker vec] input-args]
                             (when-not (= :<- marker)
                               (console :error err-header "expected :<-, got:" marker))
                             (fn inp-fn
                               ([_] (subscribe input-db vec))
                               ([_ _] (subscribe input-db vec))))

                         ;; multiple sugar pairs
                         (let [pairs   (partition 2 input-args)
                               markers (map first pairs)
                               vecs    (map second pairs)]
                           (when-not (and (every? #{:<-} markers) (every? vector? vecs))
                             (console :error err-header "expected pairs of :<- and vectors, got:" pairs))
                           (fn inp-fn
                             ([_] (map #(subscribe input-db %) vecs))
                             ([_ _] (map #(subscribe input-db %) vecs)))))]
    (register-handler! app query-id (make-subs-handler-fn inputs-fn computation-fn query-id))))

(comment
  (def play-db {})
  (reg-sub play-db
    :my-query
    ))
