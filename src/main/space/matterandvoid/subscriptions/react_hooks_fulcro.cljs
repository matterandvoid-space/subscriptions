(ns space.matterandvoid.subscriptions.react-hooks-fulcro
  (:require-macros [space.matterandvoid.subscriptions.react-hooks-fulcro])
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [goog.object :as gobj]
    ["react" :as react]
    [taoensso.timbre :as log]
    [space.matterandvoid.subscriptions.fulcro :as subs]
    [space.matterandvoid.subscriptions.impl.react-hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

;(defn use-sub-orig
;  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
;  subscription which will cause the consuming React function component to update when the subscription's value updates.
;
;  Arguments are a fulcro application whose state atom is a reagent ratom, and a subscription query vector
;  (a vector of a keyword/function and an optional hashmap of arguments).
;
;  The single-arity version takes only a query vector and will use the suscription app-context to read the fulcro app from
;  React context.
;
;  The underlying subscription is cached in a React ref so it is not recreated across re-renders.
;  By default the subscription will be re-created when the query vector to the subscription changes between renders.
;  By default uses `cljs.core/identical?` to determine if the query has changed, but the equality function `equal?` can be passed in
;  to change this behavior.
;  Thus it is expected that the subscription vector is memoized between renders and is invalidated by the calling code
;  when necessary (for example when the arguments map changes values) to achieve optimal rendering performance."
;  ([datasource query equal?]
;   (when goog/DEBUG (assert (fulcro.app/fulcro-app? datasource)))
;
;   (let [last-query (react/useRef query)
;         ref        (react/useRef nil)]
;     (when-not (.-current ref)
;       (set! (.-current ref) (subs/subscribe datasource query)))
;
;     (when-not (equal? (.-current last-query) query)
;       (println "QUERIES NOT EQUAL DISPOSING")
;       (set! (.-current last-query) query)
;       (ratom/dispose! (.-current ref))
;       (set! (.-current ref) (subs/subscribe datasource query))
;       ;(js/setTimeout (fn []
;       ;                 (ratom/dispose! (.-current ref))
;       ;                 (set! (.-current ref) (subs/subscribe datasource query))))
;       )
;
;     ;(react/useEffect (fn []
;     ;                   (fn []
;     ;                     (when (.-current ref)
;     ;                       (println "COMPONENT DID UNMOUNT - DISPSE")
;     ;                       (ratom/dispose! (.-current ref)))
;     ;                     )) #js[])
;     (common/use-reaction (.-current ref))
;     ))
;
;  ([datasource query]
;   (use-sub datasource query identical?))
;
;  ([query]
;   (use-sub (react/useContext subs/datasource-context) query identical?)))

(defn use-sub
  ([datasource query equal?]
   (when goog/DEBUG (assert (fulcro.app/fulcro-app? datasource)))
   (let [last-query (react/useRef query)
         ref        (react/useRef nil)]

     (when-not (.-current ref)
       (set! (.-current ref)
         (subs/subscribe datasource query)))

     (when-not (equal? (.-current last-query) query)
       (println "QUERIES NOT EQUAL DISPOSING")
       (set! (.-current last-query) query)
       (ratom/dispose! (.-current ref))
       (set! (.-current ref) (subs/subscribe datasource query)))

     (react/useEffect
       (fn mount [] (fn unmount [] (when (.-current ref) (ratom/dispose! (.-current ref)))))
       #js[])

     (common/use-reaction (.-current ref))))

  ([datasource query]
   (use-sub datasource query identical?))

  ([query]
   (use-sub (react/useContext subs/datasource-context) query identical?)))

(defn use-reaction-ref
  "Takes a Reagent Reaction inside a React ref and rerenders the UI component when the Reaction's value changes.
  Returns the current value of the Reaction"
  [^js ref]
  (when goog/DEBUG (when (not (gobj/containsKey ref "current"))
                     (throw (js/Error (str "use-reaction-ref hook must be passed a reaction inside a React ref."
                                        " You passed: " (pr-str ref))))))
  (common/use-reaction-ref ref))

(defn use-reaction-orig
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [reaction]
  (common/use-reaction reaction))

(defn use-reaction-in-ref
  "Takes a Reagent Reaction, uses a react ref to cache the Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [reaction]
  (let [ref (react/useRef reaction)]
    (common/use-reaction-ref ref)))
