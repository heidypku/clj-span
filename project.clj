(defproject clj-span "1.0.0-SNAPSHOT"
  :description
"clj-span - SPAN models for Ecosystem Service Assessment
Copyright 2009-2012 Gary W. Johnson (gjohnson@green-compass.org)
----------------------------------------------------------------------
This application provides a suite of spatial models that simulate the
flow of ecosystem services from the landscapes which provide them to
the people who receive them in a region determined by the user's input
maps. This implementation is based on the paper 'Service Path
Attribution Networks (SPANs): Spatially Quantifying the Flow of
Ecosystem Services from Landscapes to People' (Springer LNCS 2010 -
Johnson et al.)
----------------------------------------------------------------------"
  :dependencies [[org.clojure/clojure "1.0.0"]]
  :checksum-deps false
  ;; :warn-on-reflection true
  ;; :omit-source true
  :main clj-span.commandline)
