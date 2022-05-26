(ns space.matterandvoid.subscriptions.impl.hooks-common
  (:require
    ["react" :as react]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defn use-in-reaction
  "A react hook that takes a function with no arguments (a thunk) and runs the provided function inside a reagent `run-in-reaction`,
   returning the passed in function's value to the calling component. re-runs passed in function when any reagent reactive updates fire.

  The hook causes the consuming component to re-render at most once per frame even if the reactive callback fires more than
  once per frame."
  [deref-fn]
  (let [[output-value set-output-value!] (react/useState nil)
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
                        (set-output-value! (deref-fn))
                        (set! (.-current render-scheduled?) false)))))
                {:no-cache true})]
          (set-output-value! return-val))
        (fn cleanup-subscription []
          (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key))))
      #js[])
    output-value))
