(ns revolt.plugins.figwheel
  (:require [clojure.tools.logging :as log]
            [revolt.plugin :refer [Plugin create-plugin]]
            [revolt.utils :as utils]
            [clojure.java.io :as io]
            [figwheel.main.api :as figwheel]))

(defn ensure-relative-builds-paths
  [builds target]
  (let [ensure-relative (partial utils/ensure-relative-path target)]
    (map #(-> %
              (update-in [:compiler :output-dir] ensure-relative)
              (update-in [:compiler :output-to] ensure-relative))
         builds)))

(defn merge-with-compiler-opts
  [builds compiler-opts]
  (map #(update % :compiler merge compiler-opts) builds))

(defn filter-maybe
  [builds ids]
  (if-not ids
    builds
    (filter (fn [build]
              (some #(= (:id build) %) ids))
            builds)))

(defn init-plugin
  "Initializes figwheel plugin."

  [config]
  (reify Plugin
    (activate [this ctx]
      (if-let [cljs-opts (.config-val ctx :revolt.task/cljs)]
        (let [assets (utils/ensure-relative-path (.target-dir ctx) "assets")
              cljs-compiler (:compiler cljs-opts)
              cljs-builds   (-> (:builds cljs-opts)
                                (merge-with-compiler-opts cljs-compiler)
                                (ensure-relative-builds-paths assets))
              builds (filter-maybe cljs-builds (:builds config))]

          ;; be sure assets dir already exists in case no plugin or task created it before
          (io/make-parents assets)

          (if (seq builds)
            (let [source-paths  (mapcat :source-paths builds)
                  figwheel-conf (-> config
                                    (update :watch-dirs concat source-paths)
                                    (update :css-dirs conj assets)
                                    (dissoc :builds)
                                    (assoc  :mode :serve
                                            :rebel-readline false
                                            :open-url false))
                  builds-conf  (map #(-> %
                                         (assoc  :options (:compiler %))
                                         (dissoc :compiler
                                                 :source-paths))
                                    builds)]

              (log/infof "Starting figwheel with builds: %s" (:builds config))

              (apply figwheel/start (cons figwheel-conf builds-conf)))

            (log/error "None of configured builds found.")))
        (log/error "revolt.task/cljs task needs to be configured.")))

    (deactivate [this _]
      (log/infof "stopping figwheel.")
      (figwheel/stop-all))))
