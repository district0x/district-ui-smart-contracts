(ns district.ui.smart-contracts.events
  (:require
    [ajax.core :as ajax]
    [cljs.spec.alpha :as s]
    [day8.re-frame.async-flow-fx]
    [day8.re-frame.forward-events-fx]
    [day8.re-frame.http-fx]
    [district.ui.smart-contracts.queries :as queries]
    [district.ui.web3.queries :as web3-queries]
    [district.ui.web3.events :as web3-events]
    [district0x.re-frame.spec-interceptors :refer [validate-first-arg validate-args]]
    [re-frame.core :refer [reg-event-fx trim-v]]
    [clojure.string :as string]
    [district.ui.logging.events :as logging])
  (:require-macros [district.ui.smart-contracts.utils :refer [slurp-env-contracts]]))

(def interceptors [trim-v])
(def contracts-files-contents (slurp-env-contracts))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ATTENTION                                    ;;
;; Using :load-method :use-loaded only supports ;;
;; format: truffle-json                         ;;
;; load-bin?: false                             ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(reg-event-fx
  ::start
  interceptors
  (fn [{:keys [:db]} [{:keys [:contracts :disable-loading-at-start? :format :load-method :load-bin?]
                       :or {load-method :request}
                       :as opts}]]
    (when (and (= load-method :use-loaded)
               (or (= load-bin? true)
                   (not= format :truffle-json)))
      (throw (js/Error. "Load method :use-loaded only supported for truffle-json. No bin support yet")))
    (merge
      {:db (queries/merge-contracts db contracts)}
      (when-not disable-loading-at-start?
        {:dispatch (if (and (= load-method :use-loaded)
                            (or (= load-bin? true)
                                (not= format :truffle-json)))
                     [::logging/error "Load method :use-loaded only supported for truffle-json. No bin support yet" opts ::start]
                     (case load-method
                      :request [::load-contracts opts]
                      :use-loaded [::use-loaded-contracts opts]))}))))


(defn- ensure-slash [path]
  (if-not (string/ends-with? path "/")
    (str path "/")
    path))


(defn- get-version-param [contract-version contracts-version]
  (when (or contracts-version contract-version)
    (str "?v=" (cond
                 contract-version contract-version
                 (= contracts-version :no-cache) (.getTime (js/Date.))
                 :else contracts-version))))

(defn- get-file-path [file-type
                      {:keys [:name :path :version]}
                      {:keys [:contracts-path :contracts-version]
                       :or {contracts-path "/contracts/build/"}}]
  (str (ensure-slash (or path contracts-path))
       name "." (cljs.core/name file-type)
       (get-version-param version contracts-version)))


(defn- files-to-load [{:keys [:contracts :load-bin? :format]
                       :or {format :solc-abi-bin}
                       :as opts}]
  (reduce
    (fn [acc [key {:keys [:abi :bin] :as contract}]]
      (cond-> acc
        (not abi) (conj {:file-path (get-file-path ({:solc-abi-bin :abi
                                                     :truffle-json :json} format)
                                                   contract opts)
                         :contract-key key
                         :file-type :abi
                         :format format})

        (and load-bin?
             (not bin)
             (= format :solc-abi-bin)) (conj {:file-path (get-file-path :bin contract opts)
                                              :contract-key key
                                              :file-type :bin})))
    []
    contracts))


(reg-event-fx
  ::load-contracts
  [interceptors (validate-first-arg :district.ui.smart-contracts/opts)]
  (fn [{:keys [:db]} [{:keys [:request-timeout :contracts] :as opts
                       :or {request-timeout 10000}}]]
    (let [to-load (files-to-load opts)
          *load-batch* (atom (zipmap to-load (repeat false)))]
      {:db (queries/merge-contracts db contracts)
       :http-xhrio
       (for [{:keys [:file-path :file-type] :as contract-to-load} to-load]
         {:method :get
          :uri file-path
          :timeout request-timeout
          :response-format (if (= file-type :abi)
                             (ajax/json-response-format)
                             (ajax/text-response-format))
          :on-success [::contract-loaded contract-to-load true *load-batch*]
          :on-failure [::contract-loaded contract-to-load false *load-batch*]})})))

(reg-event-fx
 ::use-loaded-contracts
 [interceptors (validate-first-arg :district.ui.smart-contracts/opts)]
 (fn [{:keys [:db]} [{:keys [:contracts] :as opts}]]
   {:db (reduce
         (fn [r contract-key]
           (queries/assoc-contract-abi r contract-key (clj->js (get-in contracts-files-contents [contract-key :abi]))))
         (queries/merge-contracts db contracts)
         (keys contracts))
    :dispatch-n [[::contracts-loaded]]}))


(reg-event-fx
  ::contract-loaded
  interceptors
  (fn [{:keys [:db]} [{:keys [:contract-key :file-type :format] :as contract} success? *load-batch* response]]
    (swap! *load-batch* assoc contract true)
    (let [new-db (if success?
                   (condp = file-type
                     :abi (case format
                            :solc-abi-bin (queries/assoc-contract-abi db contract-key response)
                            :truffle-json (-> db
                                              (queries/assoc-contract-abi contract-key (get response "abi"))
                                              (queries/assoc-contract-bin contract-key (get response "bytecode"))))
                     :bin (queries/assoc-contract-bin db contract-key response))
                   db)]
      (merge
        (if success?
          {:dispatch [::set-contract contract-key (queries/contract new-db contract-key)]}
          {:dispatch [::contract-load-failed contract-key (queries/contract new-db contract-key) response]})
        (when (every? true? (vals @*load-batch*))
          (if (web3-queries/web3 db)
            {:dispatch-n [[::contracts-loaded]]}
            {:async-flow {:first-dispatch [::do-nothing*]
                          :rules [{:when :seen?
                                   :events [::web3-events/web3-created]
                                   :halt? true
                                   :dispatch [::contracts-loaded]}]}}))))))

(reg-event-fx
  ::do-nothing*
  (constantly nil))

(reg-event-fx
  ::set-contract
  [interceptors (validate-args (s/cat :contract-key :district.ui.smart-contracts/contract-key
                                      :contract :district.ui.smart-contracts/contract
                                      :args (s/* any?)))]
  (fn [{:keys [:db]} [contract-key contract]]
    {:db (queries/merge-contract db contract-key contract)}))

(reg-event-fx
  ::contract-load-failed
  (constantly nil))

(reg-event-fx
  ::contracts-loaded
  (constantly nil))

(reg-event-fx
  ::stop
  interceptors
  (fn [{:keys [:db]}]
    {:db (queries/dissoc-smart-contracts db)}))
