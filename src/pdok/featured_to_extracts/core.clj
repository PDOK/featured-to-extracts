(ns pdok.featured-to-extracts.core
  (:require [clojure.java.jdbc :as j]
            [pdok.transit :as t]
            [pdok.featured-to-extracts.config :as config]
            [pdok.featured-to-extracts.mustache :as m]
            [pdok.featured-to-extracts.template :as template]
            [pdok.postgres :as pg]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-time [coerce :as tc] [format :as tf]])
  (:gen-class)
  (:import (java.sql SQLException)
           (java.util UUID)))

(def ^{:private true} extract-schema
  (pg/quoted (:schema config/db)))

(def ^{:private true} delta-schema
  (pg/quoted (:schema config/dbdelta)))

(defn delta-dataset[dataset]
  (str dataset "_delta")
  )



(defn- qualified-table [table]
2  (str extract-schema "." (pg/quoted table)))

(defn- qualified-delta-table [table]
  (str delta-schema "." (pg/quoted table)))

(def ^{:private true} deltaset-table
  (qualified-delta-table "extractset"))



(def ^{:private true} extractset-table
  (qualified-table "extractset"))

(def ^{:private true} extractset-area-table
  (qualified-table "extractset_area"))

(def ^:dynamic *render-template* m/render)

(defn features-for-extract [dataset feature-type extract-type features]
  "Returns the rendered representation of the collection of features for a given feature-type inclusive tiles-set"
  (if (empty? features)
    [nil nil]
    (let [template-key (template/template-key dataset extract-type feature-type)]
      [nil (map #(hash-map :feature-type feature-type :feature % :xml (*render-template* template-key %)) features)])))


(defn features-for-delta [dataset feature-type extract-type enriched-records]
  "Returns the rendered representation of the collection of features for a given feature-type inclusive tiles-set"
  (if (empty? enriched-records)
    [nil nil]
    (let [template-key (template/template-key (delta-dataset dataset) extract-type feature-type)]
      [nil (map #(hash-map :feature-type feature-type
                           :feature (-> % (dissoc :was) (dissoc :wordt))
                           :xml (*render-template* template-key %)) enriched-records)])))



(defn- jdbc-insert-delta [tx table entries]
  (when (seq entries)
    (try
      (pg/batch-insert tx (qualified-delta-table table)
                       [:feature_type, :version, :valid_from, :valid_to, :publication, :tiles, :xml] entries)
      (catch SQLException e
        (log/with-logs ['pdok.featured.extracts :error :error] (j/print-sql-exception-chain e))
        (throw e)))))

(defn get-or-add-deltaset [tx dataset extract-type]
  "return id"
  (let [query (str "SELECT id FROM " deltaset-table " WHERE name = ? AND extract_type = ?")
        result (j/query tx [query dataset extract-type])]
    (if (empty? result)
      (do
        (j/query tx [(str "SELECT " delta-schema ".add_extractset(?, ?)") dataset extract-type])
        (get-or-add-deltaset tx dataset extract-type))
      (:id (first result)))))


(defn retrieve-previous-version[tx dataset collection extract-type versions]
  (if (seq versions)
  (let [table (str dataset "_" extract-type)
        query (str "SELECT * FROM " (qualified-delta-table table) "WHERE version "
                   " IN (" (->> versions (map (constantly "?")) (str/join ", ")) ")")
       result (pg/select tx query ["version", "xml"] versions )]
    (apply hash-map (mapcat #(list (:version  %)   (:xml %)) result)))
     {})
  )


(def ^:dynamic *get-or-add-deltaset* get-or-add-deltaset)



(defn- jdbc-insert-extract [tx table entries]
  (when (seq entries)
    (try
      (pg/batch-insert tx (qualified-table table)
                       [:feature_type, :version, :valid_from, :valid_to, :publication, :tiles, :xml] entries)
      (catch SQLException e
        (log/with-logs ['pdok.featured.extracts :error :error] (j/print-sql-exception-chain e))
        (throw e)))))





(defn get-or-add-extractset [tx dataset extract-type]
  "return id"
  (let [query (str "SELECT id FROM " extractset-table " WHERE name = ? AND extract_type = ?")
        result (j/query tx [query dataset extract-type])]
    (if (empty? result)
      (do
        (j/query tx [(str "SELECT " extract-schema ".add_extractset(?, ?)") dataset extract-type])
        (get-or-add-extractset tx dataset extract-type))
      (:id (first result)))))

(def ^:dynamic *get-or-add-extractset* get-or-add-extractset)

(defn add-extractset-area [tx extractset-id tiles]
  (let [query (str "SELECT * FROM " extractset-area-table " WHERE extractset_id = ? AND area_id = ?")]
    (doseq [area-id tiles]
      (if (empty? (j/query tx [query extractset-id area-id]))
        (pg/insert tx extractset-area-table [:extractset_id :area_id] [extractset-id area-id])))))

(defn add-metadata-extract-records [tx extractset-id tiles]
  (let [tiles (reduce clojure.set/union tiles)]
    (add-extractset-area tx extractset-id tiles)))

(def ^:dynamic *add-metadata-extract-records* add-metadata-extract-records)

(defn map-to-columns [features-for-extract]
  (map #(vector (:feature-type %) (:_version (:feature %)) (:_valid_from (:feature %)) (:_valid_to (:feature %))
                (:lv-publicatiedatum (:feature %)) (vec (:_tiles (:feature %))) (:xml %)) features-for-extract))

(defn add-delta-records [tx dataset extract-type rendered-features]
  "Inserts the xml-features and tile-set in an delta schema based on dataset, extract-type, version and feature-type,
   if schema or table doesn't exists it will be created. Most"
  (let [deltaset-id (*get-or-add-deltaset* tx dataset extract-type)]
    (do
      (jdbc-insert-delta tx (str dataset "_" extract-type) (map-to-columns rendered-features))
      (*add-metadata-extract-records* tx deltaset-id (map #(:_tiles (:feature %)) rendered-features)))
    (count rendered-features)))


(defn transform-and-add-delta [tx dataset feature-type extract-type records was-xmls wordt-xmls]
  (let [enriched-records (map #(merge % {:was (get was-xmls (:_previous_version %)) :wordt (get wordt-xmls (:_version %))}) records)
        [error features-for-delta] (features-for-delta dataset feature-type extract-type enriched-records)]
    (if (nil? error)
      (if (nil? features-for-delta)
        {}
        (let [n-inserted-records (add-delta-records tx dataset extract-type features-for-delta)]
          (log/debug "Delta records inserted: " n-inserted-records
                     (str/join "-" (list dataset feature-type extract-type)))
          features-for-delta
          ))
      (do
        (log/error "Error creating extracts" error)
        nil))))


(defn add-extract-records [tx dataset extract-type rendered-features]
  "Inserts the xml-features and tile-set in an extract schema based on dataset, extract-type, version and feature-type,
   if schema or table doesn't exists it will be created."
  (let [extractset-id (*get-or-add-extractset* tx dataset extract-type)]
    (do
      (jdbc-insert-extract tx (str dataset "_" extract-type) (map-to-columns rendered-features ))
      (*add-metadata-extract-records* tx extractset-id (map #(:_tiles (:feature %)) rendered-features)))
    (count rendered-features)))

(defn transform-and-add-extract [tx dataset feature-type extract-type features]
  (let [[error features-for-extract] (features-for-extract dataset feature-type extract-type features)]
    (if (nil? error)
      (if (nil? features-for-extract)
        {}
        (let [n-inserted-records (add-extract-records tx dataset extract-type features-for-extract)]
          (log/debug "Extract records inserted: " n-inserted-records
                     (str/join "-" (list dataset feature-type extract-type)))
         (let [ m1 (mapcat #(list (:_version (:feature %))   (:xml %)) features-for-extract)]
          (apply hash-map m1 ))))
      (do
        (log/error "Error creating extracts" error)
        nil))))

(defn- delete-version-with-valid-from-sql [table]
  (str "DELETE FROM " (qualified-table table)
       " WHERE version = ? AND valid_from = ? AND id IN (SELECT id FROM " (qualified-table table)
       " WHERE version = ? AND valid_from = ? ORDER BY id ASC LIMIT 1)"))

(defn- jdbc-delete-versions [tx table versions]
  "([version valid_from] ... )"
    (when (not= nil versions)
      (try
        (pg/batch-delete tx (qualified-table table) [:version] versions)
        (catch SQLException e
          (log/with-logs ['pdok.featured.extracts :error :error] (j/print-sql-exception-chain e))
          (throw e))))
)

(defn- delete-extracts-with-version [db dataset feature-type extract-type versions]
  (let [table (str dataset "_" extract-type)]
    (jdbc-delete-versions db table versions)))

(defn changelog->change-inserts [record]
  (condp = (:_action record)
    "new" record
    "change" record
    "close" record
    nil))

(defn changelog->deletes [record]
  (condp = (:_action record)
    "delete" (:_previous_version record)
    "change" (:_previous_version record)
    "close" (:_previous_version record)
    nil))

(def ^:dynamic *initialized-collection?* m/registered?)

(defn- process-changes [tx dataset collection extract-types changes]
  (let [batch-size 10000
        parts (partition-all batch-size changes)]
    (log/info "Creating extracts for" dataset collection extract-types)
    (doseq [extract-type extract-types]
      (loop [i 1
             remaining parts]

        (let [records (first remaining)]
          (when records
            (let [added-features (filter (complement nil?) (map changelog->change-inserts records))
                  deleted-features (filter (complement nil?) (map changelog->deletes records))]

               (let [wordt-xmls (transform-and-add-extract tx dataset collection extract-type added-features)
                    was-xmls (retrieve-previous-version tx dataset collection extract-type deleted-features)]
                (transform-and-add-delta  tx dataset collection extract-type records was-xmls wordt-xmls)

                (delete-extracts-with-version tx dataset collection extract-type deleted-features)
                (if (= 0 (mod i 10))
                  (log/info "Creating extracts, processed:" (* i batch-size)))
                )

              )
            (recur (inc i) (rest remaining))))))
    (log/info "Finished " dataset collection extract-types)))



(defn- filter-insert-data[feature]

  )

(def date-time-formatter (tf/formatters :date-time-parser))
(defn parse-time
  "Parses an ISO8601 date timestring to local date time"
  [datetimestring]
  (when-not (clojure.string/blank? datetimestring)
    (tc/to-local-date-time (tf/parse date-time-formatter datetimestring))))

(defn make-change-record [transit-line]
  (let [feature (t/from-json transit-line)]
    (merge (:attributes feature)
           {:_action (:action feature)
            :_collection (.toLowerCase (:collection feature))
            :_id (:id feature)
            :_previous_version (:previous-version feature)
            :_version (:version feature)
            :_tiles (:tiles feature)
            :_valid_from (:valid-from feature)
            :_valid_to (:valid-to feature)})))

(defn parse-changelog [in-stream]
  "Returns [dataset collection change-record], Where every line is a map with
  keys: feature-id,action,version,tiles,valid-from,old-version,old-tiles,old-valid-from,feature"
  (let [lines (line-seq (io/reader in-stream))
        version (first lines)
        collection (.toLowerCase (:collection (t/from-json (second lines))))
        ;drop collection info + header
        lines (drop 2 lines)]
    [version collection (map make-change-record lines)]))

(defn update-extracts [dataset extract-types changelog-stream]
  (let [[version collection changes] (parse-changelog changelog-stream)]
    (if-not (= version "pdok-featured-changelog-v2")
      {:status "error" :msg (str "unknown changelog version" version)}
      (if-not (every? *initialized-collection?* (map #(template/template-key dataset % collection) extract-types))
        {:status "error" :msg "missing template(s)" :collection collection :extract-types extract-types}
        (do
          (when (seq? changes)
            (let [tx (pg/begin-transaction config/db)]
              (process-changes tx dataset collection extract-types changes)
              (pg/commit-transaction tx)))
          {:status "ok" :collection collection})))))

(defn -main [template-location dataset extract-type & more]
  (let [templates-with-metadata (template/templates-with-metadata dataset template-location)]
    (if-not (some false? (map template/add-or-update-template templates-with-metadata))
      (comment (println (apply fill-extract dataset extract-type more)))
      (println "could not load template(s)"))))

;(with-open [s (file-stream ".test-files/new-features-single-collection-100000.json")]
;  (time (last (features-from-package-stream s))))
