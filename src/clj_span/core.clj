;;; Copyright 2010 Gary Johnson
;;;
;;; This file is part of clj-span.
;;;
;;; clj-span is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published
;;; by the Free Software Foundation, either version 3 of the License,
;;; or (at your option) any later version.
;;;
;;; clj-span is distributed in the hope that it will be useful, but
;;; WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;;; General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with clj-span.  If not, see <http://www.gnu.org/licenses/>.
;;;
;;;-------------------------------------------------------------------
;;;
;;; This namespace defines the run-span, simulate-service-flows, and
;;; data-preprocessing functions.  run-span is the main entry point
;;; into the SPAN system and may be called with a number of different
;;; options specifying the form of its results.

(ns clj-span.core
  (:use [clj-misc.utils          :only (seq2map mapmap constraints-1.0 def- p &)]
	[clj-span.model-api      :only (distribute-flow)]
	[clj-span.params         :only (set-global-params!)]
	[clj-span.interface      :only (provide-results)]
	[clj-misc.randvars       :only (_0_ rv-mean rv-average)]
	[clj-span.sediment-model :only (aggregate-flow-dirs)]
	[clj-misc.matrix-ops     :only (map-matrix
					make-matrix
					resample-matrix
					get-rows
					get-cols
					get-neighbors
					grids-align?
					is-matrix?
					matrix2coord-map)]
	[clj-span.analyzer       :only (theoretical-source
					inaccessible-source
					possible-source
					blocked-source
					actual-source
					theoretical-sink
					actual-sink
					theoretical-use
					inaccessible-use
					possible-use
					blocked-use
					actual-use
					possible-flow
					blocked-flow
					actual-flow)])
  (:require clj-span.carbon-model
	    clj-span.sediment-model
	    clj-span.proximity-model
	    clj-span.line-of-sight-model))

(defstruct location :id :neighbors :source :sink :use :flow-features :carrier-cache)

(defn- make-location-map
  "Returns a map of ids to location objects, one per location in the
   data layers."
  [source-layer sink-layer use-layer flow-layers]
  (let [rows (get-rows source-layer)
	cols (get-cols source-layer)]
    (into {}
	  (for [i (range rows) j (range cols) :let [id [i j]]]
	    [id (struct-map location
		  :id            id
		  :neighbors     (get-neighbors id rows cols)
		  :source        (get-in source-layer id)
		  :sink          (get-in sink-layer   id)
		  :use           (get-in use-layer    id)
		  :flow-features (mapmap identity #(get-in % id) flow-layers)
		  :carrier-cache (atom ()))]))))

(def- double>0?      #(and (float? %)   (pos? %)))
(def- integer>=1?    #(and (integer? %) (>= % 1)))
(def- number>=1?     #(and (number? %)  (>= % 1)))
(def- nil-or-matrix? #(or (nil? %) (is-matrix? %)))

(defn- zero-layer-below-threshold
  "Takes a two dimensional array of RVs and replaces all values whose
   means are less than the threshold with _0_."
  [threshold layer]
  (map-matrix #(if (< (rv-mean %) threshold) _0_ %) layer))

(defn run-span
  [{:keys [source-layer  source-threshold
	   sink-layer    sink-threshold
	   use-layer     use-threshold
	   flow-layers   trans-threshold
	   rv-max-states downscaling-factor
	   source-type   sink-type
	   use-type      benefit-type
	   flow-model    result-type]
    :or {source-threshold   0.0
	 sink-threshold     0.0
	 use-threshold      0.0
	 trans-threshold    0.01
	 rv-max-states      10
	 downscaling-factor 1}}]
  (constraints-1.0
   {:pre [(every? is-matrix?     [source-layer use-layer])
	  (every? nil-or-matrix? (cons sink-layer (vals flow-layers)))
	  (apply  grids-align?   (remove nil? (list* source-layer sink-layer use-layer (vals flow-layers))))
	  (every? double>0?      [source-threshold sink-threshold use-threshold trans-threshold])
	  (integer>=1? rv-max-states)
	  (number>=1?  downscaling-factor)
	  (every? #{:finite :infinite} [source-type sink-type use-type])
	  (#{:rival :non-rival} benefit-type)
	  (#{"LineOfSight" "Proximity" "Carbon" "Sediment"} flow-model)
	  (#{:cli-menu :closure-map} result-type)]})
  ;; Initialize global parameters
  (set-global-params! {:rv-max-states      rv-max-states
		       :trans-threshold    trans-threshold
		       :source-type        source-type
		       :sink-type          sink-type
		       :use-type           use-type
		       :benefit-type       benefit-type})
  ;; Preprocess data layers (downsampling and zeroing below their thresholds)
  (let [rows             (get-rows source-layer)
	cols             (get-cols source-layer)
	scaled-rows      (int (/ rows downscaling-factor))
	scaled-cols      (int (/ cols downscaling-factor))
	preprocess-layer (fn [l t] (if l
				     (zero-layer-below-threshold t (resample-matrix scaled-rows scaled-cols rv-average l))
				     (make-matrix scaled-rows scaled-cols (constantly _0_))))
	[scaled-source-layer scaled-sink-layer scaled-use-layer] (map preprocess-layer
								      [source-layer     sink-layer     use-layer]
								      [source-threshold sink-threshold use-threshold])
	scaled-flow-layers (seq2map flow-layers (fn [[name matrix]]
						  [name (resample-matrix
							 scaled-rows
							 scaled-cols
							 (if (= name "Hydrosheds") aggregate-flow-dirs rv-average)
							 matrix)]))
	;; Run flow model and return the results
	cache-layer  (distribute-flow flow-model
				      scaled-source-layer
				      scaled-sink-layer
				      scaled-use-layer
				      scaled-flow-layers)
	results-menu (apply array-map
			    (mapcat (fn [[name f]] [name (& (p resample-matrix rows cols rv-average) f)])
				    (array-map
				     "Source - Theoretical"  #(theoretical-source  scaled-source-layer scaled-use-layer)
				     "Source - Inaccessible" #(inaccessible-source scaled-source-layer scaled-use-layer cache-layer)
				     "Source - Possible"     #(possible-source     cache-layer)
				     "Source - Blocked"      #(blocked-source      cache-layer)
				     "Source - Actual"       #(actual-source       cache-layer)
				     "Sink   - Theoretical"  #(theoretical-sink    scaled-source-layer scaled-sink-layer scaled-use-layer)
				     "Sink   - Actual"       #(actual-sink         cache-layer)
				     "Use    - Theoretical"  #(theoretical-use     scaled-source-layer scaled-use-layer)
				     "Use    - Inaccessible" #(inaccessible-use    scaled-source-layer scaled-use-layer cache-layer)
				     "Use    - Possible"     #(possible-use        cache-layer)
				     "Use    - Blocked"      #(blocked-use         cache-layer)
				     "Use    - Actual"       #(actual-use          cache-layer)
				     "Flow   - Possible"     #(possible-flow       cache-layer flow-model)
				     "Flow   - Blocked"      #(blocked-flow        cache-layer flow-model)
				     "Flow   - Actual"       #(actual-flow         cache-layer flow-model))))]
    (provide-results result-type
		     results-menu
		     source-layer
		     sink-layer
		     use-layer
		     flow-layers)))
