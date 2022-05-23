(ns helix-example
  (:require
    ["react-dom" :as react-dom]
    ["react" :as react]
    [reagent.ratom :as ratom]
    [taoensso.timbre :as log]
    [space.matterandvoid.subscriptions.core :as subs :refer [defsub reg-sub]]
    [space.matterandvoid.subscriptions.react-hook :refer [use-sub use-sub-map]]
    [goog.object :as gobj]
    [helix.core :refer [$]]
    [helix.dom :as dom]))

(defn D
  ([el] (react/createElement el))
  ([el props]
   (if (or (react/isValidElement props) (string? props) (number? props)
         (satisfies? INamed props))
     (react/createElement (name el) nil (cond-> props (satisfies? INamed props) name))
     (react/createElement (name el) props)))
  ([el props & c]
   (if (or (map? props) (and (object? props) (not (react/isValidElement props))))
     (do
       (println 'in 'one el)
       (react/createElement (name el) (clj->js props) (into-array c)))
     (do
       (println 'in 'two el)
       (react/createElement (name el) nil (into-array (into [props] c)))))))

(defonce db_ (ratom/atom {:a-number    5
                          :another-num 100}))

(reg-sub :a-number :-> :a-number)
(reg-sub :another-num :-> :another-num)

(defn inc! [data_]
  (swap! data_ update :a-number inc))

(defn second-hook []
  (let [{:keys [my-number] :as args} (use-sub-map db_ {:my-number [:a-number]
                                                       :number2   [:another-num]})]
    (dom/div
      (dom/h4 "another: " (:number2 args))
      (str "second hook: " my-number))))

(defn first-hook []
  (let [[the-count set-count] (react/useState 0)
        sub-val (use-sub db_ [:a-number])]
    (react/useEffect (fn [] (println "IN EFFECT")) #js[the-count])
    (dom/div {:style {:padding 10 :border "1px dashed"}}
      (dom/h3
        (str "The number is : " sub-val))
      ($ second-hook)
      (dom/button {:on-click #(inc! db_)} "INC!")
      (D :button {:key "butt" :onClick #(set-count (inc the-count))}
        (str "count is " the-count)))))

(defn my-react-comp [props]
  (D first-hook)
  ;(dom/div nil (first-hook))
  #_(react/createElement "div" nil (first-hook)))

(defn ^:export init []
  (println "HELLO")
  (react-dom/render (my-react-comp nil) js/app))

(defn ^:dev/after-load refresh []
  (react-dom/render (my-react-comp nil) js/app)
  (println "HI")
  )

(comment
  (let [prop "hell"]
    (js-obj "hello" 5 prop 100))
  )
