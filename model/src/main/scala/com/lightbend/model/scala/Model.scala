package com.lightbend.model.scala

/**
  * Created by boris on 5/9/17.
  * Basic trait for model
  */
trait Model {
  def score(input : AnyVal) : AnyVal
  def cleanup() : Unit
  def toBytes() : Array[Byte]
  def getType : Long
}