(ns br.dev.zz.oiti
  (:refer-clojure :exclude [load])
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [ring.core.protocols :as rcp])
  (:import (org.snakeyaml.engine.v2.api Load LoadSettings)))

;; https://spec.openapis.org/oas/3.1/schema/2022-02-27

(set! *warn-on-reflection* true)

(defn load
  [x]
  (let [loader (Load. (.build (LoadSettings/builder)))]
    (with-open [rdr (io/reader x)]
      (.loadFromReader loader rdr))))

(def http-methods
  #{"delete" "get" "head" "options" "patch" "post" "put" "trace"})
(defn path->json-pointer
  [vs]
  (str "#" (string/join "/"
             (cons ""
               (map (fn [v]
                      (-> v
                        str
                        (string/replace #"~" "~0")
                        (string/replace #"/" "~1")))
                 vs)))))


(defn json-pointer->path
  [s]
  (mapv (fn [s]
          (-> s
            str
            (string/replace #"~1" "/")
            (string/replace #"~0" "~")))
    (rest (string/split s #"/"))))

(defn $deref
  [document {:strs [$ref]
             :as   object}]
  (if-not (get object "$ref")
    object
    (merge (into {}
             (remove (comp #{"$ref"} key))
             object)
      (when $ref
        ($deref document (get-in document (json-pointer->path $ref)))))))


(defn path-regex
  [path]
  (string/replace path #"(\{[^\}]+\})" "([^/]+)"))

(defn path-params-names
  [path]
  (loop [input path
         params []]
    (let [[match param] (re-find #"\{([^\}]+)\}"
                          input)]
      (if param
        (recur (string/replace input match "")
          (conj params param))
        params))))

(s/def ::document some?)
(s/def ::handlers (s/map-of string? fn?))

(defn match-route?
  [uri {::keys [path-re params-names]
        :as    path-info}]
  (when-let [match (re-matches path-re uri)]
    (merge path-info
      (when params-names
        {::path-params (zipmap params-names
                         (rest match))}))))

(defn parameters->object-schema
  [params]
  (when (seq params)
    {"type"       "object"
     "properties" (into {}
                    (for [{:strs [schema name]} params]
                      [name schema]))
     "required"   (vec (for [{:strs [required name]} params
                             :when required]
                         name))}))

(defn compile-routes
  [{::keys [document handlers]}]
  (let [not-implemented (constantly {:status 503})]
    (into {}
      (map (fn [[k vs]]
             [k (mapv second vs)]))
      (group-by first
        (for [[path-pattern $path-item] (get document "paths")
              :let [path-item ($deref document $path-item)]
              [method $operation] (select-keys path-item http-methods)
              :let [operation ($deref document $operation)
                    {:keys [query header cookie path]} (group-by (fn [{:strs [in]}]
                                                                   (keyword in))
                                                         (map (partial $deref document)
                                                           (concat
                                                             (get path-item "parameters")
                                                             (get operation "parameters"))))
                    handler (get handlers (or (get operation "operationId")
                                            (path->json-pointer ["paths" path-pattern method]))
                              not-implemented)
                    params-names (path-params-names path-pattern)]]
          [(keyword method) (merge {::path-re   (re-pattern (path-regex path-pattern))
                                    ::handler   handler
                                    ::operation operation}
                              (when (seq query)
                                {::query-schema (parameters->object-schema query)})
                              (when (seq header)
                                {::header-schema (parameters->object-schema header)})
                              (when (seq cookie)
                                {::cookie-schema (parameters->object-schema cookie)})
                              (when (seq params-names)
                                (let [path-param-by-name (into {}
                                                           (map (juxt #(get % "name")
                                                                  identity))
                                                           path)]
                                  {::params-schema (parameters->object-schema
                                                     (for [param params-names]
                                                       {"name"     param
                                                        "required" true
                                                        "schema"   (get path-param-by-name param
                                                                     {"type" "string"})}))
                                   ::params-names  params-names})))])))))

(defn match-route!
  [{::keys [operation path-params params-schema]}
   {:keys [uri request-method]
    :as   ring-request}]
  (merge ring-request
    (when operation
      {::operation operation})
    (when params-schema
      {::params-schema params-schema
       ::path-params   path-params})))

(defn ->ring-handler
  [opts]
  (let [routes (compile-routes opts)
        not-found (constantly {:status 404})]
    (fn [{:keys [uri request-method]
          :as   ring-request}]
      (let [{::keys [handler]
             :or    {handler not-found}
             :as    route} (some (partial match-route? uri)
                             (get routes request-method))
            {:as   ring-response
             :keys [content]} (handler (match-route! route ring-request))]
        (merge (select-keys ring-response [:status :headers :body])
          (when (contains? ring-response :content)
            {:body (reify rcp/StreamableResponseBody
                     (write-body-to-stream [body response output-stream]
                       (with-open [w (io/writer output-stream)]
                         (json/write content w))))}))))))

(s/fdef ->ring-handler
  :args (s/cat :args (s/keys :req [::document
                                   ::handlers]))
  :ret fn?)
