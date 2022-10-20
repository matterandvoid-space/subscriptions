(ns space.matterandvoid.subscriptions.react-hooks
  (:require-macros [space.matterandvoid.subscriptions.react-hooks])
  (:require
    [goog.object :as gobj]
    ["react" :as react]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.impl.react-hooks-common :as common]
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
  subscription which will cause the consuming React function component to update when the subscription's value updates.

  Arguments are a Reagent RAtom `datasource`, and a subscription query vector
  (a vector of a keyword/function and an optional hashmap of arguments).

  The single-arity version takes only a query vector and will use the suscription app-context to read the fulcro app from
  React context.

  The underlying subscription is cached in a React ref so it is not re-created across re-renders.
  By default the subscription will be re-created when the query vector to the subscription changes between renders.
  By default uses `cljs.core/identical?` to determine if the query has changed, but the equality function `equal?` can be passed in
  to change this behavior.
  Thus it is expected that the subscription vector is memoized between renders and is invalidated by the calling code
  when necessary (for example when the arguments map changes values) to achieve optimal rendering performance."
  ([datasource query equal?]
   (when goog/DEBUG (assert (fulcro.app/fulcro-app? datasource)))

   (let [last-query (react/useRef query)
         ref        (react/useRef nil)]
     (when-not (.-current ref)
       (set! (.-current ref) (subs/subscribe datasource query)))

     (when-not (equal? (.-current last-query) query)
       (set! (.-current last-query) query)
       (ratom/dispose! (.-current ref))
       (set! (.-current ref) (subs/subscribe datasource query)))

     (common/use-reaction-ref ref)))

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

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [reaction]
  (common/use-reaction reaction))
