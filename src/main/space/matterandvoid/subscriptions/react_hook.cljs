(ns space.matterandvoid.subscriptions.react-hook
  (:require
    [goog.object :as gobj]
    ["react" :as react]
    [space.matterandvoid.subscriptions.impl.hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.core :as subs]))


;; All of these subscription hooks use a React Ref to wrap the Reaction.
;; The reason for doing so is so that React does not re-create the Reaction object each time the component is rendered.
;;
;; This is safe because the ref's value never changes for the lifetime of the component (per use of use-reaction)
;; Thus the caution to not read .current from a ref during rendering doesn't apply because we know it never changes.
;;
;; The guideline exists for refs whose underlying value will change between renders, but we are just using it
;; as a cache local to the component in order to not recreate the Reaction with each render.
;;
;; References:
;; - https://beta.reactjs.org/apis/react/useRef#referencing-a-value-with-a-ref
;; - https://beta.reactjs.org/apis/react/useRef#avoiding-recreating-the-ref-contents

(defn use-sub
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming react function component to update when the subscription's value updates.

  Arguments are a reagent ratom `data-source`, and a subscription query vector (vector of keyword and an optional hashmap of
  arguments).

  The single-arity version takes only a query map and will use the suscription app-context to read the fulcro app from
  React context."
  ([data-source query]
   (when goog/DEBUG (assert (ratom/ratom? data-source)))
   (let [ref (react/useRef nil)]
     (when-not (.-current ref) (set! (.-current ref) (subs/subscribe data-source query)))
     (common/use-reaction ref)))
  ([query]
   (let [data-source (react/useContext subs/datasource-context)]
     (use-sub data-source query))))

(defn use-sub-map
  "A react hook that subscribes to multiple subscriptions, the return value of the hook is the return value of the
  subscriptions which will cause the consuming react function component to update when the subscriptions' values update.

  Takes a data source (reagent ratom) and a hashmap
  - keys are keywords (qualified or simple) that you make up.
  - values are subscription vectors.
  Returns a map with the same keys and the values are the subscriptions subscribed and deref'd (thus, being their current values).

  The single-arity version takes only a query map and will use the suscription app-context to read the fulcro app from
  React context."
  ([data-source query-map]
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
  ([query-map]
   (let [data-source (react/useContext subs/datasource-context)]
     (use-sub-map data-source query-map))))

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
