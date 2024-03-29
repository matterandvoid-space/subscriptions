(ns space.matterandvoid.subscriptions.impl.fulcro
  (:require
    [com.fulcrologic.fulcro.algorithms.normalized-state :refer [dissoc-in]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.impl.loggers :refer [console]]
    [space.matterandvoid.subscriptions.impl.subs :as subs]))

(defn get-input-db-signal
  "Given the storage for the subscriptions return an atom containing a map
  (this is the 'db' in re-frame parlance).
  this assumes you've set your fulcro app's state-atom to be reagent reactive atom."
  [app]
  ;; This is a kind of hack to allow passing in the fulcro state map to subscriptions
  ;; which is useful in contexts like mutation helpers which operate on the hashmap and some arguments, but would
  ;; be annoying to pass the fulcro app to.
  (cond
    (fulcro.app/fulcro-app? app) (::fulcro.app/state-atom app)
    (ratom/deref? app) app
    :else (ratom/atom app)))

;; for other proxy interfaces (other than fulcro storage) this has to be an atom of a map.
;; this is here for now just to inspect it at the repl
(defonce subs-cache_ (atom {}))
(comment
  @subs-cache_
  (count @subs-cache_)
  (take 3 @subs-cache_)
  (key (first @subs-cache_))
  (val (first @subs-cache_))
  )

;; here we also have the option of storing the subscription cache in the fulcro app.
;; that way it will be visible to fulcro users
;; (you probably want it nested one level like under ::subscriptions key or something
;; so there isn't an explosion in the top level keyspace
;; it's a tradeoff, it may make more sense to just add integration with fulcro inspect via the
;; existing tracing calls.

(def sub-meta-name-kw* :space.matterandvoid.subscriptions.fulcro/sub-name)

(defn sub-missing-name! [sub-fn query]
  (throw (#?(:cljs js/Error. :clj Exception.)
           (str "Subscription function does not have a name and cannot be cached! "
             (or (-> sub-fn meta sub-meta-name-kw*) (pr-str query))))))

(defn sub-fn->sub-name [sub-fn query]
  (if-let [sub-name (-> sub-fn meta sub-meta-name-kw*)]
    sub-name
    (sub-missing-name! sub-fn query)))

(defn get-cache-key [_app [query-key :as query-v]]
  (cond
    (or (symbol? query-key) (keyword? query-key))
    query-v

    #?(:cljs (instance? cljs.core/MetaFn query-key)
       :clj false)
    (let [sub-name (sub-fn->sub-name query-key query-v)]
      [sub-name (second query-v)])

    (fn? query-key)
    #?(:cljs (sub-missing-name! query-key query-v)
       :clj [(sub-fn->sub-name query-key query-v) (second query-v)])

    :else
    query-v))

(defn get-subscription-cache [app] subs-cache_ #_(atom {}))
(defn cache-lookup [app cache-key] (when app (get @(get-subscription-cache app) cache-key)))

(def app-state-key ::state)
(def subs-key ::subs)
(defn subs-state-path [k v] [app-state-key subs-key v])
(defn state-path ([] [app-state-key]) ([k] [app-state-key k]) ([k v] [app-state-key k v]))

(defn subs-cache->fulcro-app-state [app]
  (swap! (::fulcro.app/state-atom app)
    assoc-in [:space.matterandvoid/subscriptions] (subs/map-vals deref @subs-cache_)))

(defonce handler-registry_ (atom {}))

(defn get-handler
  "Returns a \"handler\" function registered for the subscription with the given `id`.
  Fulcro app and 'query-id' -> subscription handler function.
  Lookup in the place where the query-id -> handler functions are stored."
  [id]
  (if (fn? id)
    (or (-> id meta :space.matterandvoid.subscriptions.fulcro/subscription) id)
    (get-in @handler-registry_ (subs-state-path subs-key id))))

(defn register-handler!
  "Returns `handler-fn` after associng it in the map."
  [id handler-fn]
  (swap! handler-registry_ assoc-in (subs-state-path subs-key id)
    (fn [& args] (apply handler-fn args)))
  handler-fn)

(defn clear-handlers
  ;; clear all handlers
  ([_app] (reset! handler-registry_ {}))
  ([_app id]
   (if (get-handler id)
     (swap! handler-registry_ dissoc-in (subs-state-path subs-key id))
     (console :warn "Subscriptions: can't clear handler for" (str id ". Handler not found.")))))

;; -------------------
;; api

(defn set-memoize-fn! [f] (subs/set-memoize-fn! f))
(defn set-args-merge-fn! [f] (subs/set-args-merge-fn! f))

(defn reg-sub
  [query-id & args]
  (apply subs/reg-sub
    get-input-db-signal get-handler register-handler! get-subscription-cache cache-lookup get-cache-key
    query-id args))

(defn reg-layer2-sub
  "Registers a handler function that returns a Reagent RCursor instead of a Reagent Reaction.
  Accepts a single keyword, a vector path into or a function which takes your db atom and arguments map passed to subscribe
  and must return a vector path to be used for the cursor."
  [query-id path-vec-or-fn]
  (subs/reg-layer2-sub
    get-input-db-signal register-handler!
    query-id path-vec-or-fn))

(defn subscribe
  "Given a `query` vector, returns a Reagent `reaction` which will, over
  time, reactively deliver a stream of values. Also known as a `Signal`.

  To obtain the current value from the Signal, it must be dereferenced"
  [app query]
  (subs/subscribe get-handler cache-lookup get-subscription-cache get-cache-key
    app query))

(defn <sub
  "Subscribe and deref a subscription, returning its value, not a reaction."
  [app query]
  (let [value (subscribe app query)]
    (when value @value)))

(defn clear-sub
  ([registry]
   (clear-handlers registry))
  ([registry query-id]
   (clear-handlers registry query-id)))

(defn reg-sub-raw [query-id handler-fn] (subs/reg-sub-raw register-handler! query-id handler-fn))

(defn clear-subscription-cache! [registry] (subs/clear-subscription-cache! get-subscription-cache registry))

(defn sub-fn
  "Takes a function that returns either a Reaction or RCursor. Returns a function that when invoked delegates to `f` and
   derefs its output. The returned function can be used in subscriptions."
  [meta-fn-key f]
  (subs/sub-fn meta-fn-key f))

(defn make-sub-fn [meta-sub-kw query-id sub-args]
  (subs/make-sub-fn get-input-db-signal meta-sub-kw subscribe query-id sub-args))

#?(:clj
   (defmacro deflayer2-sub
     "Takes a symbol for a subscription name and a way to derive a path in your fulcro app db. Returns a function subscription
     which itself returns a Reagent RCursor.
     Supports a vector path, a single keyword, or a function which takes the RAtom datasource and the arguments map passed to subscribe and
     must return a path vector to use as an RCursor path.

     Examples:

     (deflayer2-sub my-subscription :a-path-in-your-db)

     (deflayer2-sub my-subscription [:a-path-in-your-db])

     (deflayer2-sub my-subscription (fn [db-atom sub-args-map] [:a-key (:some-val sub-args-map])))
     "
     [meta-sub-kw sub-name ?path]
     `(subs/deflayer2-sub ~meta-sub-kw get-input-db-signal ~sub-name ~?path)))

#?(:clj
   (defmacro defsubraw
     "Creates a subscription function that takes the datasource ratom and optionally an args map and
     returns the subscription value. The return value is wrapped in a Reaction for you, so you do not need to."
     [meta-sub-kw sub-name args body]
     `(subs/defsubraw ~meta-sub-kw get-input-db-signal ~sub-name ~args ~body)))

(defn parse-reg-sub-args [args]
  (subs/parse-reg-sub-args get-input-db-signal subscribe "space.matterandvoid.subscriptions: " args))

(def deref-input-signals subs/deref-input-signals)
