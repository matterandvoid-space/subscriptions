(ns space.matterandvoid.subscriptions.react-hook
  (:require
    [goog.object :as gobj]
    ["react" :as react]
    [space.matterandvoid.subscriptions.impl.hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.core :as subs]))

(defn use-sub
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming react function component to update when the subscription's value updates.

  Arguments are a reagent ratom `data-source`, and a subscription query vector (vector of keyword and an optional hashmap of
  arguments)."
  [data-source query]
  (when goog/DEBUG (assert (ratom/ratom? data-source)))
  (let [ref (react/useRef nil)]
    (when-not (.-current ref) (set! (.-current ref) (subs/subscribe data-source query)))
    (common/use-reaction ref)))

(defn use-sub-map
  "A react hook that subscribes to multiple subscriptions, the return value of the hook is the return value of the
  subscriptions which will cause the consuming react function component to update when the subscriptions' values update.

  Takes a data source (reagent ratom) and a hashmap
  - keys are keywords (qualified or simple) that you make up.
  - values are subscription vectors.
  Returns a map with the same keys and the values are the subscriptions subscribed and deref'd (thus, being their current values)."
  [data-source query-map]
  (when goog/DEBUG (assert (ratom/ratom? data-source)))
  (when goog/DEBUG (assert (map? query-map)))
  (let [ref (react/useRef nil)]
    (when-not (.-current ref)
      (set! (.-current ref)
        (ratom/make-reaction
          (fn []
            (reduce-kv (fn [acc k query-vec] (assoc acc k (subs/<sub data-source query-vec)))
              {} query-map)))))
    (common/use-reaction ref)))

(defn use-reaction-ref
  "Takes a Reagent Reaction inside a React ref and rerenders the UI component when the Reaction's value changes.
  Returns the current value of the Reaction"
  [^js r]
  (when goog/DEBUG (when (not (gobj/containsKey r "current"))
                     (throw (js/Error (str "use-reaction-ref hook must be passed a reaction inside a React ref."
                                        " You passed: " (pr-str r))))))
  (common/use-reaction r))

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [r]
  (let [ref (react/useRef r)]
    (common/use-reaction ref)))
