(ns hooks-example
  (:require
    ["react-dom" :as react-dom]
    ["react" :as react]
    [reagent.ratom :as ratom]
    [space.matterandvoid.subscriptions.core :as subs :refer [defsub reg-sub]]
    [space.matterandvoid.subscriptions.react-hook :refer [use-sub use-sub-map]]))

(defn $
  "Create a new React element from a valid React type.
  Adapted from helix.core"
  [type & args]
  (let [?p (first args), ?c (rest args), type' (cond-> type (keyword? type) name)]
    (if (map? ?p)
      (apply react/createElement type' (clj->js ?p) ?c)
      (apply react/createElement type' nil args))))

(defonce db_ (ratom/atom {:a-number    5
                          :another-num 100}))

(reg-sub :a-number :-> :a-number)
(reg-sub :another-num :-> :another-num)

(reg-sub ::twice-num1 :<- [:a-number] :-> #(* 2 %))
(reg-sub ::twice-num2 :<- [:another-num] :-> #(* 2 %))

(defn inc! [data_]
  (swap! data_ update :a-number inc))

(defn second-hook []
  (let [{:keys [my-number] :as args} (use-sub-map db_ {:my-number  [:a-number]
                                                       :number2    [:another-num]
                                                       :twice-num1 [::twice-num1]
                                                       :twice      [::twice-num2]})]
    ($ "div"
      ($ "h4" "num 2: " (:number2 args))
      ($ "h4" "twice num 1: " (:twice-num1 args))
      ($ "h4" "twice num 2: " (:twice args))
      (str "my number is : " my-number))))

(defn first-hook []
  (let [[the-count set-count] (react/useState 0)
        sub-val (use-sub db_ [:a-number])]
    (react/useEffect (fn [] (println "IN EFFECT")) #js[the-count])
    ($ :div {:style {:padding 10 :border "1px dashed"}}
      ($ :h3
        (str "The number is : " sub-val))
      ($ second-hook)
      ($ :button {:onClick #(inc! db_)} "INC!")
      ($ :button {:onClick #(set-count (inc the-count))}
        (str "count is " the-count)))))

(defn my-react-comp [props]
  ($ first-hook)
  ;(dom/div nil (first-hook))
  #_(react/createElement "div" nil (first-hook)))

(defn ^:export init []
  (react-dom/render (my-react-comp nil) js/app))

(defn ^:dev/after-load refresh []
  (react-dom/render (my-react-comp nil) js/app))
