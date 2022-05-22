(ns space.matterandvoid.subscriptions.react-hook-fulcro
  (:require
    ["react" :as react]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.fulcro :as subs]
    [taoensso.timbre :as log]))

(defn use-sub
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming react function component to update when the subscription's value updates.

  Arguments are a fulcro application whose state atom is a reagent ratom and a subscription query vector
  (2-tuple of keyword and an optional hashmap of arguments)."
  [data-source query]
  (let [[render-count set-render-count!] (react/useState 0)
        [subscription-value set-subscription-value!] (react/useState nil)
        reaction-key "reaction"
        reaction-obj (react/useRef #js{})]
    (react/useEffect
      (fn setup-subscription []
        (log/info "In use-sub on mount")
        (let [return-val
              (ratom/run-in-reaction
                (fn [] (subs/<sub data-source query))
                (.-current reaction-obj)
                reaction-key
                (fn on-react! [] (log/info "REACTED!")
                  (set-render-count! (fn [c] (log/info "set rnder  count!" c) (inc c)))
                  (set-subscription-value! (subs/<sub data-source query)))
                {:no-cache true})]
          (set-subscription-value! return-val)
          (set-render-count! (fn [c] (log/info "set rnder  count!" c) (inc c))))
        (fn cleanup-subscription []
          (log/info "in use-sub on unmount")
          (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key))))
      #js[])

    subscription-value))
