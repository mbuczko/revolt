(ns revolt.tasks.info
  (:require [revolt.shell :refer [git]]
            [revolt.utils :as utils]
            [clojure.java.io :as io]))

(def datetime-formatter
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn invoke [kv target]
  (when-let [info (merge
                   {:sha (git rev-parse --short HEAD)
                    :tag (git describe --abbrev=0 --tags HEAD)
                    :branch (git rev-parse --abbrev-ref HEAD)
                    :timestamp (.format (java.time.LocalDateTime/now) datetime-formatter)} kv)]

    (let [path (utils/ensure-relative-path target "project.edn")]
      (with-open [out (io/writer path)] (spit out info))
      info)))