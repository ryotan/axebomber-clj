(ns axebomber.style
  (:use [axebomber.util])
  (:import [org.apache.poi.xssf.usermodel XSSFWorkbook]
           [org.apache.poi.ss.util CellUtil CellRangeAddress]
           [org.apache.poi.ss.usermodel CellStyle IndexedColors]))

(def style-sets (atom {}))
(def styled (atom #{}))

(defn- apply-style-each-cells [sheet x y w h {class-name :class}]
  (doseq [cx (range x (+ x w)) cy (range y (+ y h))]
    (when-not (some #(= [cx cy] %) @styled)
      (let [style-index (+ (if (= cy y) 1 0)
                           (if (= cx (+ x w -1)) 2 0)
                           (if (= cy (+ y h -1)) 4 0)
                           (if (= cx x) 8 0))]
        (.setCellStyle (get-cell sheet cx cy)
                       (get-in @style-sets [(or class-name :default) style-index]))
        (swap! styled conj [cx cy])))))

(defn- apply-style-merged-cells [sheet x y w h {class-name :class align :text-align}]
  (apply-style-each-cells sheet x y w h {:class class-name})
  (let [cell (get-cell sheet x y)]
    (.addMergedRegion sheet (CellRangeAddress. y (+ y h -1) x (+ x w -1)))
    (.setCellStyle cell
                   (get-in @style-sets [(or class-name :default) 15]))
    (CellUtil/setCellStyleProperty
     cell
     (.getWorkbook sheet)
     CellUtil/ALIGNMENT (case align
                          "center" CellStyle/ALIGN_CENTER
                          "right"  CellStyle/ALIGN_RIGHT))))

(defn apply-style [sheet x y w h style]
  (if (= (get style :text-align :left) :left)
    (apply-style-each-cells sheet x y w h style)
    (apply-style-merged-cells sheet x y w h style)))

(defn create-style-set
  [wb style-name & {:keys [border-type background-color]
                    :or {border-type CellStyle/BORDER_THIN}}]
  (let [styles (for [border-ptn (range 16)]
                 (let [style (.createCellStyle wb)]
                   (when (not= (bit-and border-ptn 1) 0)
                     (.setBorderTop style border-type))
                   (when (not= (bit-and border-ptn 2) 0)
                     (.setBorderRight style border-type))
                   (when (not= (bit-and border-ptn 4) 0)
                     (.setBorderBottom style border-type))
                   (when (not= (bit-and border-ptn 8) 0)
                     (.setBorderLeft style border-type))
                   (when background-color
                     (.setFillForegroundColor style background-color)
                     (.setFillPattern style CellStyle/SOLID_FOREGROUND))
                   style))]
    (swap! style-sets assoc style-name (vec styles))))

