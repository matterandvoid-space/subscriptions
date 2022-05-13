(ns space.matterandvoid.subscriptions.subs-test
  (:require
    [cljs.test :refer [deftest is testing]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [datascript.core :as d]
    [com.fulcrologic.fulcro.components :as c]
    [space.matterandvoid.subscriptions.impl.memoize :refer [memoize-fn]]
    [space.matterandvoid.subscriptions.impl.subs :as sut]))


(enable-console-print!)

(defn get-input-db [app] (fulcro.app/current-state app))
(defn get-input-db-signal [app] (::fulcro.app/state-atom app))
(defonce subs-cache (atom {}))
(defn get-subscription-cache [app] subs-cache)
(defn cache-lookup [app query-v]
  (when app (get @(get-subscription-cache app) query-v)))
(def app-state-key ::state)
(def subs-key ::subs)
(defn subs-state-path [k v] [app-state-key subs-key v])
(defn state-path ([] [app-state-key]) ([k] [app-state-key k]) ([k v] [app-state-key k v]))

(defonce handler-registry (atom {}))

(comment @handler-registry)

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [id handler-fn]
  (swap! handler-registry assoc-in (subs-state-path subs-key id) (fn [& args] (apply handler-fn args)))
  handler-fn)

(defn get-handler [id]
  (get-in @handler-registry (subs-state-path subs-key id)))

(deftest memoize-fn-test
  (let [counter_ (volatile! 0)
        add      (fn add [x y]
                   (vswap! counter_ inc)
                   (println "EXECUTING") (+ x y))
        mem-add  (memoize-fn {:max-args-cached-size 2 :max-history-size 3} add)]
    (mem-add 1 1) (mem-add 1 1) (mem-add 1 1) (mem-add 1 1) (mem-add 1 1)
    (is (= 1 @counter_))))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def schema {:todo/id {:db/unique :db.unique/identity}})
(def conn (d/create-conn schema))
(defn all-todos [conn]
  (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] (d/db conn))
  )
(memoize-fn {:max-args-cached-size 2 :max-history-size 3} (fn [conn]
                                                            ()))
(comment
  (d/transact! conn [(make-todo (random-uuid) "hello4-7")])
  ()
  (d/create-conn schema)
  )

;; setup a test environment and pass all of the callbacks needed
;(deftest test-reg-sub-clj-repl
;  (sut/reg-sub :a-sub (fn [db _] (:a db)))
;  (sut/reg-sub :b-sub (fn [db _] (:b db)))
;  (sut/reg-sub :a-b-sub (fn [_ _] (mapv subs/subscribe [[:a-sub] [:b-sub]])) (fn [[a b] _] {:a a :b b}))
;
;  (let [test-sub (sut/subscribe [:a-b-sub])]
;    (reset! db/app-db {:a 1 :b 2})
;    (is (= {:a 1 :b 2} @test-sub))
;    (swap! db/app-db assoc :b 3)
;    (is (= {:a 1 :b 3} @test-sub))))

(set! sut/memoize-fn memoize-fn)

(defn reg-sub [query-id & args]
  (apply sut/reg-sub
    get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup
    query-id args))

(defn subscribe
  [?app query]
  (sut/subscribe get-handler cache-lookup get-subscription-cache (c/any->app ?app) query))

(reg-sub :hello
  (fn [db] (:hello db)))

(defonce fulcro-app (fulcro.app/fulcro-app {:initial-db {:hello 500}}))

(comment
  @(subscribe fulcro-app [:hello])
  (get-input-db-signal fulcro-app)
  (get-input-db fulcro-app)

  ((get-handler :hello) fulcro-app [:hello])
  )
