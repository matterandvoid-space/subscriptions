(ns space.matterandvoid.subscriptions.subscription-functions
  (:require
    [clojure.string :as str]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom :refer [make-reaction]
     ]
    [space.matterandvoid.subscriptions.impl.subs :as sub]
    [clojure.test :refer [deftest is testing]]))

(defn hello []
  500
  )

(defn fn-name [f]
  #?(:cljs (.-name ^js f)
     :clj  (str/replace (str f) #"@.*$" "")))

(comment
  (str hello)
  (let [x hello]
    ;(x)
    ;(.-name x)
    (fn-name x)
    ; cljs => "space$matterandvoid$subscriptions$subscription_functions$hello"
    ; clj  => "space.matterandvoid.subscriptions.subscription_functions$hello"
    )
  )


;; reg-sub just calls register-handler!
;;

; desired API

(defonce db_ (ratom/atom {:a 'hello}))
;; only ever takes
(defn a-subscription []
  (make-reaction (fn [] (:a @db_))))


;#?(:clj (defmacro defsub [nme & args] ))
(defn get-input-db-signal [x])
(defn error [& args])
(defn console [& args])
(defn get-handler [query-id])
(defn cache-lookup [id])
(defn get-subscription-cache [])
(def args-merge-fn merge)
(defn merge-update-args [subs-vec args*] (cond-> subs-vec (map? args*) (update 1 args-merge-fn args*)))

(defn cache-and-return! [a b c d] d)

(defn subscribe
  "Takes a datasource and query and returns a Reaction."
  [get-handler cache-lookup get-subscription-cache
   app query]
  (assert (vector? query) (str "Queries must be vectors, you passed: " query))
  (let [cnt      (count query),
        query-id (first query)]
    (assert (or (= 1 cnt) (= 2 cnt)) (str "Query must contain only one map for subscription " query-id))
    (when (and (= 2 cnt) (not (map? (get query 1))))
      (throw (error "Args to the query vector must be one map for subscription " query-id "\n" "Received: " (pr-str (get query 1)))))
    (let [cached (cache-lookup app query)]
      (if cached cached
                 (let [handler-fn (get-handler query-id)]
                   (assert handler-fn (str "Subscription handler for the following query is missing\n\n" (pr-str query-id) "\n"))
                   (if (nil? handler-fn)
                     (console :error (str "No subscription handler registered for: " query-id "\n\nReturning a nil subscription."))
                     (cache-and-return! get-subscription-cache app query (handler-fn app query))))))))

(defn parse-inputs [args]
  (let [err-header ""]
    (case (count args)
      ;; no `inputs` function provided - give the default
      0 (do
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
            ([app] (subscribe get-handler cache-lookup get-subscription-cache app signal-vec))
            ([app args*]
             ;(log/info "one pair: args " (merge-update-args signal-vec args*))
             (subscribe get-handler cache-lookup get-subscription-cache app (merge-update-args signal-vec args*)))))

      ;; multiple :<- pairs
      (let [pairs   (partition 2 args)
            markers (map first pairs)
            vecs    (map second pairs)]
        (when-not (and (every? #{:<-} markers) (every? vector? vecs))
          (console :error err-header "expected pairs of :<- and vectors, got:" pairs))

        (fn inp-fn
          ([app] (map #(subscribe get-handler cache-lookup get-subscription-cache app %) vecs))
          ([app args*]
           (map #(subscribe get-handler cache-lookup get-subscription-cache app (merge-update-args % args*))
             vecs))))))
  )

;(defsub sub2 :<- [a-subscription]
;  )
;; the end goal is putting functions in :<- input vectors to reg-sub
;; there are only a few touch points that should need to be updated. I'm using this file to discover those
;; and get a better idea about how this works

;; when you call subscribe, the code uses the query to look up a function
;; that function returns a reaction
;; that's it.
(comment

  "
  Reg sub just takes the inputs the user declares and makes a function that will deref them
  "

  @(a-subscription))
