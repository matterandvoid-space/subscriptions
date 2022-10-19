(ns space.matterandvoid.subscriptions.react-hooks-fulcro
  (:require-macros [space.matterandvoid.subscriptions.react-hooks-fulcro])
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [goog.object :as gobj]
    ["react" :as react]
    [space.matterandvoid.subscriptions.impl.react-hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.fulcro :as subs]))

(defn use-sub
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming react function component to update when the subscription's value updates.

  Will cause the consuming component to re-render only once per animation frame (using requestAnimationFrame) when the subscription updates.

  Arguments are a fulcro application whose state atom is a reagent ratom and a subscription query vector
  (a vector of a keyword and an optional hashmap of arguments).

  The single-arity version takes only a query map and will use the suscription app-context to read the fulcro app from
  React context."
  ([data-source query]
   (when goog/DEBUG (assert (fulcro.app/fulcro-app? data-source)))
   (let [last-query (react/useRef query)
         ref        (react/useRef nil)]
     (when-not (.-current ref) (set! (.-current ref) (subs/subscribe data-source query)))
     (when (not= (.-current last-query) query)
       (set! (.-current last-query) query)
       (ratom/dispose! (.-current ref))
       (set! (.-current ref) (subs/subscribe data-source query)))
     (common/use-reaction-ref ref)))
  ([query]
   (let [data-source (react/useContext subs/datasource-context)]
     (use-sub data-source query))))

(defn use-reaction-ref
  "Takes a Reagent Reaction inside a React ref and rerenders the UI component when the Reaction's value changes.
  Returns the current value of the Reaction"
  [^js r]
  (when goog/DEBUG (when (not (gobj/containsKey r "current"))
                     (throw (js/Error (str "use-reaction-ref hook must be passed a reaction inside a React ref."
                                        " You passed: " (pr-str r))))))
  (common/use-reaction-ref r))

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [r]
  (let [ref (react/useRef r)]
    (common/use-reaction-ref ref)))
