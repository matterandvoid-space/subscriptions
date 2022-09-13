(ns hooks-example
  (:require
    ["react-dom/client" :as react-dom]
    ["react" :as react]
    [reagent.ratom :as ratom]
    [space.matterandvoid.subscriptions.core :as subs :refer [defsub reg-sub <sub]]
    [space.matterandvoid.subscriptions.react-hook :refer [use-sub use-sub-map use-reaction]]))

(defn $
  "Create a new React element from a valid React type.
  Adapted from helix.core"
  [type & args]
  (let [?p (first args), ?c (rest args), type' (cond-> type (keyword? type) name)]
    (if (map? ?p)
      (apply react/createElement type' (clj->js ?p) ?c)
      (apply react/createElement type' nil args))))

(defonce db_ (ratom/atom {:a-number    5
                          :a-string    "hello"
                          :another-num 100}))

(reg-sub :a-number :-> :a-number)
(reg-sub :a-string :-> :a-string)
(reg-sub :another-num :-> :another-num)

(reg-sub ::twice-num1 :<- [:a-number] :-> #(* 2 %))
(reg-sub ::twice-num2 :<- [:another-num] :-> #(* 2 %))

(defn inc! [data_] (swap! data_ update :a-number inc))
(defn inc-alot! [data_]
  (println "run")
  (js/setTimeout
    #(swap! data_ update :a-number inc)) (range 100))

(def max-run 100)

(defn run-it! [data_]
  (let [run-count_ (volatile! 0)]
    (js/setTimeout
      (fn cb []
        (vswap! run-count_ inc)
        (println "swap!")
        (swap! data_ update :a-number inc)
        (swap! data_ update :a-string str "moretext")
        (when (< @run-count_ max-run)
          (js/setTimeout cb 0)))
      0)))

(defn third-hook []
  (let [output (use-reaction (ratom/make-reaction (fn [] (+ 100 (<sub db_ [:a-number])))))]
    ($ :h1 "use-in-reaction hook: " output)))

(defn second-hook []
  (let [{:keys [my-number my-str] :as args} (use-sub-map db_ {:my-number  [:a-number]
                                                              :number2    [:another-num]
                                                              :my-str     [:a-string]
                                                              :twice-num1 [::twice-num1]
                                                              :twice      [::twice-num2]})]
    ($ "div"
      ($ "div" "str is: " ($ :p {:style {:overflowWrap "break-word"}} my-str))
      ($ "h4" "num 2: " (:number2 args))
      ($ "h4" "twice num 1: " (:twice-num1 args))
      ($ "h4" "twice num 2: " (:twice args))
      (str "my number is : " my-number))))

(defn first-hook []
  (let [[the-count set-count] (react/useState 0)
        sub-val (use-sub db_ [:a-number])]
    (react/useEffect (fn [] (println "IN EFFECT") js/undefined) #js[the-count])
    (println "DRAW FIRST HOOK")
    ($ :div {:style {:padding 10 :border "1px dashed"}}
      ($ :h3 (str "The number is : " sub-val))
      ($ third-hook)
      ($ second-hook)
      ($ :button {:onClick #(inc! db_)} "INC!")
      ($ :button {:onClick #(inc-alot! db_)} "INC a lot!")
      ($ :button {:onClick #(run-it! db_)} "RUN!")
      ($ :button {:onClick #(set-count (inc the-count))}
        (str "count is " the-count)))))

(defn my-react-comp [props]
  ($ first-hook)
  ;(dom/div nil (first-hook))
  #_(react/createElement "div" nil (first-hook)))

(defonce root (react-dom/createRoot (js/document.getElementById "app")))
(defn ^:export init []
  (.render root (my-react-comp nil))

  ;(react-dom/render (my-react-comp nil) js/app)
  )

(defn ^:dev/after-load refresh []
  (.render root (my-react-comp nil))
  ;(react-dom/render (my-react-comp nil) js/app)
  )
