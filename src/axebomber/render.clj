(ns axebomber.render
  (:use [axebomber.util]
        [axebomber.style])
  (:import [org.apache.poi.ss.util CellUtil]
           [org.apache.poi.ss.usermodel CellStyle IndexedColors]))

(declare render)

(def ^{:doc "Regular expression that parses a CSS-style id and class from an element name."
       :private true}
  re-tag #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

(defn- literal? [expr]
  (or (string? expr)
      (keyword? expr)))

(defn- merge-attributes [{:keys [id class]} map-attrs]
  (->> map-attrs
       (merge (if id {:id id}))
       (merge-with #(if %1 (str %1 " " %2) %2) (if class {:class class}))))

(defn normalize-element
  "Ensure an element vector is of the form [tag-name attrs content]."
  [[tag & content]]
  (when (not (or (keyword? tag) (symbol? tag) (string? tag)))
    (throw (IllegalArgumentException. (str tag " is not a valid element name."))))
  (let [[_ tag id class] (re-matches re-tag (as-str tag))
        tag-attrs        {:id id
                          :class (if class (.replace ^String class "." " "))}
        map-attrs        (first content)]
    (if (map? map-attrs)
      [tag (merge-attributes tag-attrs map-attrs) (next content)]
      [tag tag-attrs content])))

(defn- inherit-size [sheet x y & {:keys [colspan] :or {colspan 1}}]
  (loop [cx x, cols 1]
    (let [cell (get-cell sheet cx (dec y))]
      (if-let [merged-cell (some #(when (.isInRange % (.getRowIndex cell) (.getColumnIndex cell)) %)
                                 (map #(.getMergedRegion sheet %) (range (. sheet getNumMergedRegions))))]
        (if (>= cols colspan)
          (- (.getLastColumn merged-cell) (.getFirstColumn merged-cell) -1)
          (recur (+ (.getLastColumn merged-cell) 1) (inc cols)))
        (if (not= (.. cell getCellStyle getBorderRight) CellStyle/BORDER_NONE)
          (if (>= cols colspan)
            (- cx x -1)
            (recur (inc cx) (inc cols)))
          (if (> cx 255)
            (- cx x -1)
            (recur (inc cx) cols)))))))

(defn render-literal [sheet x y lit]
  (let [lines (-> (map-indexed
                   (fn [idx v]
                     (let [c (get-cell sheet x (+ y idx))]
                       (.setCellValue c v)))
                   (clojure.string/split (str lit) #"\n"))
                count)]
    [1 lines lit]))

(defn render-horizontal [sheet x y tag attrs content]
  (let [max-height (atom 0)
        cy (+ y (get attrs :margin-top 0))]
    (loop [cx (+ x (get attrs :margin-left 0)), content content, children []]
      (let [[w h child] (render sheet cx cy (first content))
             cx (+ cx w)]
        (reset! max-height (max h @max-height))
        (if (not-empty (rest content))
          (recur cx (rest content) (conj children child))
          [(- cx x) @max-height (conj children child)])))))

(defn render-vertical [sheet x y tag attrs content]
  (let [max-width (atom 0)
        cx (+ x (get attrs :margin-left 0))]
    (loop [cy (+ y (get attrs :margin-top 0)), content content, children []]
      (let [[w h child] (render sheet cx cy (first content))
             cy (+ cy h)]
        (reset! max-width (max w @max-width))
        (if (not-empty (rest content))
          (recur cy (rest content) (conj children child))
          [@max-width
           (- cy y (- (get attrs :margin-bottom 0)))
           (conj children child)])))))

(defmulti render-tag (fn [sheet x y tag & rst] tag))

(defmethod render-tag "table" [sheet x y tag attrs content]
  (render-vertical sheet x y tag attrs content))

(defmethod render-tag "tr" [sheet x y tag attrs content]
  (let [[w h td-tags] (render-horizontal sheet x y tag attrs content)]
    (loop [cx x, idx 0]
      (let [[td-tag td-attrs _] (nth td-tags idx)
            size (get td-attrs :size 3)
            align (get td-attrs :text-align "left")]
        (apply-style sheet cx y size h td-attrs)
        (if (< idx (dec (count td-tags)))
          (recur (+ cx size) (inc idx))
          [w h [:tr attrs td-tags]])))))

(defmethod render-tag "td" [sheet x y tag attrs content]
  (let [[w h child] (render sheet x y content)
         size (or (and (:colspan attrs) (inherit-size sheet x y :colspan (:colspan attrs)))
                  (:size attrs)
                  (inherit-size sheet x y))
         attrs (assoc attrs :size size)]
    [size h [:td attrs child]]))

(defmethod render-tag "ul" [sheet x y tag attrs content]
  (let [list-style-type (get attrs :list-style-type "・")
        [w h children] (render-vertical sheet x y tag attrs content)]
    (->> children
         (tree-seq sequential? seq)
         (filter #(and (vector? %) (= (first %) "li")))
         (map #(render-literal sheet (:x (second %)) (:y (second %)) list-style-type))
         doall)
    [w h children]))

(defmethod render-tag "ol" [sheet x y tag attrs content]
  (let [list-style-type (get attrs :list-style-type "・")
        [w h children] (render-vertical sheet x y tag attrs content)]
    (->> children
         (tree-seq sequential? seq)
         (filter #(and (vector? %) (= (first %) "li")))
         (map-indexed #(render-literal sheet (:x (second %2)) (:y (second %2)) (inc %1)))
         doall)
    [w h children]))

(defmethod render-tag "li" [sheet x y tag attrs content]
  (let [[w h children] (render sheet (inc x) y content)]
    [w h [tag (merge attrs {:x x :y y}) content]]))

(defmethod render-tag :default
  [sheet x y tag attrs content]
  (render-vertical sheet x y tag attrs content))

(defn- element-render-strategy
  "Returns the compilation strategy to use for a given element."
  [sheet x y [tag attrs & content]]
  (cond
    (every? literal? (list tag attrs))
      ::all-literal                    ; e.g. [:span "foo"]
    (and (literal? tag) (map? attrs))
      ::literal-tag-and-attributes     ; e.g. [:span {} x]
    (literal? tag)
      ::literal-tag                    ; e.g. [:span x]
    :else
      ::default))

(defmulti render-element element-render-strategy)

(defmethod render-element ::all-literal
  [sheet x y [tag & literal]]
  (let [[tag tag-attrs _] (normalize-element [tag])]
    (render-tag sheet x y tag tag-attrs literal)))

(defmethod render-element ::literal-tag-and-attributes
  [sheet x y [tag attrs & content]]
  (let [[tag attrs _] (normalize-element [tag attrs])]
    (render-tag sheet x y tag attrs content)))

(defmethod render-element ::literal-tag
  [sheet x y [tag & content]]
  (let [[tag tag-attrs _] (normalize-element [tag])]
    (render-tag sheet x y tag tag-attrs content)))

(defn- merge-attributes [{:keys [id class]} map-attrs]
  (->> map-attrs
       (merge (if id {:id id}))
       (merge-with #(if %1 (str %1 " " %2) %2) (if class {:class class}))))

(defn render-seq [sheet x y content]
  (loop [cx x, cy y, content content, children []]
    (let [[w h child] (render sheet cx cy (first content))]
      (if (not-empty (rest content))
        (recur cx (+ cy h) (rest content) (conj children child))
        [(+ cx w (- x)) (+ cy h (- y)) (conj children child)]))))

(defn render [sheet x y expr]
  (cond
   (vector? expr) (render-element sheet x y expr)
   (literal? expr) (render-literal sheet x y expr)
   (seq? expr) (render-seq sheet x y expr)
   (nil? expr) [1 1 ""]
   :else (render-literal sheet x y (str expr))))
