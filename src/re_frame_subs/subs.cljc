(ns re-frame-subs.subs
  (:require
    [re-frame-subs.interop :refer [add-on-dispose! debug-enabled? make-reaction ratom? deref? dispose! reagent-id]]
    [re-frame-subs.loggers :refer [console]]
    [re-frame-subs.trace :as trace :include-macros true]
    [re-frame-subs.utils :refer [first-in-vector]]))

;; -- cache -------------------------------------------------------------------
;;
;; De-duplicate subscriptions. If two or more equal subscriptions
;; are concurrently active, we want only one handler running.
;; Two subscriptions are "equal" if their query vectors test "=".

(defn clear-subscription-cache!
  "calls `on-dispose` for each cached item,
   which will cause the value to be removed from the cache"
  [get-subscription-cache app]
  (doseq [[_ rxn] @(get-subscription-cache app)] (dispose! rxn))
  (if (not-empty @(get-subscription-cache app))
    (console :warn "re-frame: The subscription cache isn't empty after being cleared")))

(defn cache-and-return!
  "cache the reaction r"
  [get-subscription-cache app query-v reaction]
  (let [cache-key          query-v
        subscription-cache (get-subscription-cache app)]
    ;(console :info "cache-and-return!" subscription-cache)
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
                                ;(console :info "ABOUT TO ASSOC , cache key: " cache-key)
                                ;(console :info "ABOUT TO ASSOC , cache is : " query-cache)
                                (assoc query-cache cache-key reaction)))
    (trace/merge-trace! {:tags {:reaction (reagent-id reaction)}})
    reaction)) ;; return the actual reaction

;; -- subscribe ---------------------------------------------------------------

(defn subscribe
  [get-input-db get-handler cache-lookup get-subscription-cache
   app query]
  (let [input-db (get-input-db app)]
    (trace/with-trace {:operation (first-in-vector query)
                       :op-type   :sub/create
                       :tags      {:query-v query}}
      ;(console :info (str "subs. cache-lookup: " query))
      (if-let [cached (cache-lookup app query)]
        (do
          (trace/merge-trace! {:tags {:cached?  true
                                      :reaction (reagent-id cached)}})
          ;(console :info (str "subs. returning cached " cached))
          cached)
        (let [query-id   (first-in-vector query)
              handler-fn (get-handler app query-id)]
          ;(console :info "DO NOT HAVE CACHED")
          ;(console :info (str "subs. computing subscription" ))
          (assert handler-fn (str "Handler for query missing, " (pr-str query-id)))

          (trace/merge-trace! {:tags {:cached? false}})
          (if (nil? handler-fn)
            (do (trace/merge-trace! {:error true})
                (console :error (str "re-frame: no subscription handler registered for: " query-id ". Returning a nil subscription.")))
            (do
              ;(console :info "Have handler. invoking")
              (cache-and-return! get-subscription-cache app query (handler-fn input-db query)))))))))

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
  ;(console :info "deref-input-signals: " query-id ", " signals)
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
          ;_             (console :info "IN SUBS HANDLER 1")
          reaction      (make-reaction
                          (fn []
                            (trace/with-trace {:operation (first-in-vector query-vec)
                                               :op-type   :sub/run
                                               :tags      {:query-v  query-vec
                                                           :reaction @reaction-id}}
                              ;(console :info "IN the reaction callbak")
                              (let [subscription-output (computation-fn (deref-input-signals subscriptions query-id) query-vec)]
                                ;(console :info "IN the reaction callback 2 sub output: " subscription-output)
                                (trace/merge-trace! {:tags {:value subscription-output}})
                                subscription-output))))]

      ;_ (console :info "IN SUBS HANDLER 2, reagent id: " (reagent-id reaction))
      (reset! reaction-id (reagent-id reaction))
      reaction)))

(defn reg-sub
  "db, fully qualified keyword for the query id
  optional positional args: "
  [get-input-db get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
   app query-id & args]
  (let [computation-fn (last args)
        _              (assert (ifn? computation-fn) "Last arg should be function - your computation function.")
        input-args     (butlast args) ;; may be empty, or one signal fn, or pairs of  :<- / vector
        err-header     (str "re-frame: reg-sub for " query-id ", ")
        inputs-fn      (case (count input-args)
                         ;; no `inputs` function provided - give the default
                         0
                         (do
                           ;(console :info "CASE 0")
                           (fn
                             ([_] (get-input-db-signal app))
                             ([_ _] (get-input-db-signal app))))

                         ;; a single `inputs` fn
                         1 (let [f (first input-args)]
                             ;(console :info "CASE 1")
                             (when-not (fn? f)
                               (console :error err-header "2nd argument expected to be an inputs function, got:" f))
                             f)

                         ;; one sugar pair
                         2 (let [[marker vec] input-args]
                             ;(console :info "CASE 2")
                             (when-not (= :<- marker)
                               (console :error err-header "expected :<-, got:" marker))
                             (fn inp-fn
                               ([_] (subscribe get-input-db get-handler cache-lookup get-subscription-cache app vec))
                               ([_ _] (subscribe get-input-db get-handler cache-lookup get-subscription-cache app vec))))

                         ;; multiple sugar pairs
                         (let [pairs   (partition 2 input-args)
                               markers (map first pairs)
                               vecs    (map second pairs)]
                           ;(console :info "CASE 3")
                           (when-not (and (every? #{:<-} markers) (every? vector? vecs))
                             (console :error err-header "expected pairs of :<- and vectors, got:" pairs))
                           (fn inp-fn
                             ([_] (map #(subscribe get-input-db get-handler cache-lookup get-subscription-cache app %) vecs))
                             ([_ _] (map #(subscribe get-input-db get-handler cache-lookup get-subscription-cache app %) vecs)))))]
    (register-handler! app query-id (make-subs-handler-fn inputs-fn computation-fn query-id))))

(comment
  (def play-db {})
  (reg-sub play-db
    :my-query
    ))
