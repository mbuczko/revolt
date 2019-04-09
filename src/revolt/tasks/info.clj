(ns revolt.tasks.info
  (:require [revolt.shell :refer [git]]
            [revolt.utils :as utils]))

(def ^:private datetime-formatter
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn invoke [ctx kv target]
  (utils/timed
   "INFO"
   (-> ctx
       (assoc :sha (git rev-parse --short HEAD)
              :tag (git describe --abbrev=0 --tags HEAD)
              :branch (git rev-parse --abbrev-ref HEAD)
              :timestamp (.format (java.time.LocalDateTime/now) datetime-formatter))
       (merge kv))))
