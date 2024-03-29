(ns space.matterandvoid.subscriptions.react-hooks
  (:require-macros [space.matterandvoid.subscriptions.react-hooks])
  (:require
    [goog.object :as gobj]
    ["react" :as react]
    [space.matterandvoid.subscriptions.core :as subs]
    [space.matterandvoid.subscriptions.impl.react-hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defn use-context [c] (react/useContext c))

(defn use-sub-fn
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming React function component to update when the subscription's value updates.

  Arguments are a Reagent RAtom `datasource`, and a subscription query vector
  (a vector of a keyword/function and an optional hashmap of arguments).

  The single-arity version takes only a query vector and will use the suscription app-context to read the RAtom datasource from
  React context.

  The underlying subscription is cached in a React ref so it is not re-created across re-renders.
  By default the subscription will be re-created when the query vector to the subscription changes between renders.
  By default uses `cljs.core/identical?` to determine if the query has changed, but the equality function `equal?` can be passed in
  to change this behavior.
  Thus it is expected that the subscription vector is memoized between renders and is invalidated by the calling code
  when necessary (for example when the arguments map changes values) to achieve optimal rendering performance."
  ([datasource query equal?]
   (when goog/DEBUG (assert (ratom/ratom? datasource)))
   (common/use-sub subs/subscribe datasource query equal?))

  ([datasource query]
   (use-sub-fn datasource query identical?))

  ([query]
   (use-sub-fn (react/useContext subs/datasource-context) query identical?)))

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [reaction]
  (common/use-reaction reaction))

(defn use-reaction-in-ref
  "Takes a Reagent Reaction, uses a react ref to cache the Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [reaction]
  (let [ref (react/useRef reaction)]
    (common/use-reaction (.-current ref))))

(defn use-datasource "Returns the currently bound datasource-context using react/useContext."
  [] (react/useContext subs/datasource-context))
