(ns space.matterandvoid.subscriptions.impl.hooks-common
  (:require
    ["react" :as react]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defn use-sub-common
  "A react hook that runs the provided function inside a reagent `run-in-reaction`. In the reaction's reactive callback
   will cause the component that uses the hook to render once per animation frame."
  [deref-fn]
  (let [[subscription-value set-subscription-value!] (react/useState nil)
        render-scheduled? (react/useRef false)
        reaction-key      "reaction"
        reaction-obj      (react/useRef #js{})]
    (react/useEffect
      (fn setup-subscription []
        (let [return-val
              (ratom/run-in-reaction
                deref-fn
                (.-current reaction-obj)
                reaction-key
                (fn on-react! []
                  (when-not (.-current render-scheduled?)
                    (set! (.-current render-scheduled?) true)
                    (js/requestAnimationFrame
                      (fn [_]
                        (set-subscription-value! (deref-fn))
                        (set! (.-current render-scheduled?) false)))))
                {:no-cache true})]
          (set-subscription-value! return-val))
        (fn cleanup-subscription []
          (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key))))
      #js[])
    subscription-value))
