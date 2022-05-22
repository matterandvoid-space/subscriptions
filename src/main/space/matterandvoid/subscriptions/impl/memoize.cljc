(ns space.matterandvoid.subscriptions.impl.memoize)

(defn memoize-fn
  "Returns a function which is memoized, with an eviction policy.
  For now it will retain up to 'n' unique invocations of input to output. When buffer/cache of 'n' distinct input calls is full will
  evict the oldest first.
  This saves the history of the arguments called in a queue to allow determining which args to evict based on the call
  history."
  ([f] (memoize-fn {:max-args-cached-size 100 :max-history-size 50} f))
  ([{:keys [max-args-cached-size max-history-size]} f]
   (let [cache_                                 (atom {:args->data   {}
                                                       :args-history #?(:cljs #queue[] :clj clojure.lang.PersistentQueue/EMPTY)})
         lookup-sentinel #?(:cljs (js-obj) :clj (Object.))]
     (fn [& args]
       ;(println "memoized called with: " args)
       (let [{:keys [args-history args->data]} @cache_
             v (get args->data args lookup-sentinel)]
         (swap! cache_
           #(cond-> %
              ;; the size of the cache is limited by the total key-value pairs
              (and (= (count (keys args->data)) max-args-cached-size)
                (not (contains? args->data args)))
              ;; remove the oldest (LRU) argument
              ;; make room forthe new args->value pair
              (update :args->data dissoc (peek args-history))

              (= (count args-history) max-history-size) (update :args-history pop)

              ;; cache miss, assoc new kv pair

              (identical? v lookup-sentinel)
              #?(:cljs
                 ((fn [db]
                    ;(println "Not cached, computing..." args)
                    (update db :args->data assoc args (apply f args))))
                 :clj
                 ((fn [db]
                    ;(println "Not cached, computing..." args)
                    (update db :args->data assoc args
                      (try (apply f args)
                           (catch clojure.lang.ArityException _
                             (f (first args))))))))

              ;; save the args history
              true (update :args-history conj args)))
         (get (:args->data @cache_) args))))))
