(ns hooks-example
  (:require
    ["react-dom/client" :as react-dom]
    ["react" :as react]
    [reagent.ratom :as ratom]
    [space.matterandvoid.subscriptions.core :as subs :refer [defsub reg-sub <sub]]
    [space.matterandvoid.subscriptions.react-hook :refer [use-sub use-sub-map use-reaction use-reaction-ref]]))

(def my-atom (ratom/atom {:a {:nested {:2-nested 500}}}))
(def my-cursor (ratom/cursor my-atom [:a :nested]))

(defn $
  "Create a new React element from a valid React type.
  Adapted from helix.core"
  [type & args]
  (let [?p (first args), ?c (rest args), type' (cond-> type (keyword? type) name)]
    (if (map? ?p)
      (apply react/createElement type' (clj->js ?p) ?c)
      (apply react/createElement type' nil args))))

(defonce db_ (ratom/atom {:a-number        5
                          :a-string        "hello"
                          :show-component? true
                          :level1          {:level2 {:level3 500}}
                          :another-num     100}))
(defonce lvl2-cursor_ (ratom/cursor db_ [:level1 :level2]))

(comment (deref lvl2-cursor_)
  (swap! db_ update-in [:level1 :level2 :level3] inc)
  (get-in @db_ [:level1 :level2]))
(reg-sub :show-comp? :-> :show-component?)
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
  (let [output2 (use-reaction (ratom/make-reaction (fn [] (+ 100 (<sub db_ [:a-number])))))
        ;reaction (react/useRef (ratom/make-reaction (fn [] (+ 100 (<sub db_ [:a-number])))))
        ;output   (use-reaction reaction)
        ]
    ($ :h1 "use-in-reaction hook: " output2)))

(defonce start-state (get-in @db_ [:level1 :level2]))

(defonce add-watchX
  (add-watch lvl2-cursor_ :dan (fn [key curs] (println "IN CURSOR WATCH: " @curs))))

(comment
  (swap! db_ update-in [:level1 :level2 :level3] inc)
  (identical? start-state (get-in @db_ [:level1 :level2]))
  (deref lvl2-cursor_)
  (swap! db_ update-in [:level1 :level2 :level3] inc)
  (swap! db_ update-in [:level1 :level2 :level3] inc)
  (get-in @db_ [:level1 :level2]))

(def cursor-hook
  (react/memo
    (fn [] cursor-hook
      (let [cursor-val (use-reaction lvl2-cursor_)]
        (println "Rendering level2 cursor cursor-hook")
        ($ "div" "level 2 cursor: "
          ($ "button" {:onClick #(swap! db_ update-in [:level1 :level2 :level3] inc)} "Nested inc")
          ($ "div" "cursor1 "
            ($ "pre" (pr-str cursor-val))))))))

;(def cursor-hook2
;  (react/memo
;    (fn [] cursor-hook
;      (let [cursor2 (use-in-reaction (fn [] @lvl2-cursor_))]
;        (println "Rendering level2 cursor cursor-hook2")
;        ($ "div"
;          "level 2 cursor: "
;          ($ "button" {:onClick #(swap! db_ update-in [:level1 :level2 :level3] inc)} "Nested inc")
;          ($ "div" "cursor2" ($ "pre" (pr-str cursor2))))))))

(def cursor-hook3
  (react/memo
    (fn [] cursor-hook
      (let [cursor2 (use-reaction lvl2-cursor_)]
        (println "Rendering hook3 cursor-hook3")
        ($ "div"
          "use selector reaction: "
          ($ "button" {:onClick (fn []
                                  (println "CLICK")
                                  (swap! db_ update-in [:level1 :level2 :level3] inc))} "Nested inc")
          ($ "div" "cursor3" ($ "pre" (pr-str cursor2))))))))

(defn second-hook []
  (let [show-comp? (use-sub db_ [:show-comp?])
        {:keys [my-number my-str show-comp?] :as args} (use-sub-map db_ {:my-number  [:a-number]
                                                                         :number2    [:another-num]
                                                                         :my-str     [:a-string]
                                                                         :twice-num1 [::twice-num1]
                                                                         :show-comp? [:show-comp?]
                                                                         :twice      [::twice-num2]})]
    ($ "div"
      ($ "div" "str is: " ($ :p {:style {:overflowWrap "break-word"}} my-str))
      ($ cursor-hook)
      ;($ cursor-hook2)
      ($ "hr")
      ($ "button" {:onClick (fn [] (swap! db_ update-in [:show-component?] not))} "Hide cusor component")
      (when show-comp? ($ cursor-hook3))
      (when show-comp? ($ cursor-hook3))
      (when show-comp? ($ cursor-hook3))
      (when show-comp? ($ cursor-hook3))
      (when show-comp? ($ cursor-hook3))
      (when show-comp? ($ cursor-hook3))
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
