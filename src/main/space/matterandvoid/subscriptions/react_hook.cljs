(ns space.matterandvoid.subscriptions.react-hook
  (:require
    ["react" :as react]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.core :as subs]))

(defn use-sub
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming react function component to update when the subscription's value updates.

  Arguments are a reagent ratom and a subscription query vector (2-tuple of keyword and an optional hashmap of
  arguments)."
  [data-source query]
  (when goog/DEBUG (assert (ratom/ratom? data-source)))
  (let [[render-count set-render-count!] (react/useState 0)
        [subscription-value set-subscription-value!] (react/useState nil)
        reaction-key "reaction"
        reaction-obj (react/useRef #js{})]
    (react/useEffect
      (fn setup-subscription []
        (let [return-val
              (ratom/run-in-reaction
                (fn [] (subs/<sub data-source query))
                (.-current reaction-obj)
                reaction-key
                (fn on-react! []
                  (set-render-count! (fn [c] (inc c)))
                  (set-subscription-value! (subs/<sub data-source query)))
                {:no-cache true})]
          (set-subscription-value! return-val)
          (set-render-count! (fn [c] (inc c))))
        (fn cleanup-subscription []
          (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key))))
      #js[])

    subscription-value))

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
  (let [[render-count set-render-count!] (react/useState 0)
        [subscription-value set-subscription-value!] (react/useState nil)
        reaction-key  "reaction"
        reaction-obj  (react/useRef #js{})
        deref-signals (fn [] (->> query-map
                               (reduce-kv
                                 (fn [acc k query-vec] (assoc acc k (subs/<sub data-source query-vec)))
                                 {})))]
    (react/useEffect
      (fn setup-subscription []
        (let [return-val
              (ratom/run-in-reaction
                deref-signals
                (.-current reaction-obj)
                reaction-key
                (fn on-react! []
                  (set-render-count! (fn [c] (inc c)))
                  (set-subscription-value! (deref-signals)))
                {:no-cache true})]
          (set-subscription-value! return-val)
          (set-render-count! (fn [c] (inc c))))
        (fn cleanup-subscription []
          (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key))))
      #js[])

    subscription-value))
