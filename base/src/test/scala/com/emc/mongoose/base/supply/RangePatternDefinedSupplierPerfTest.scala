package com.emc.mongoose.base.supply

import org.junit.Test
import org.junit.Assert

import java.util
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

final class RangePatternDefinedSupplierPerfTest {
	
	private val BATCH_SIZE = 0x1000
	private val TIME_LIMIT_SEC = 50
	
	@Test @throws[Exception]
	def testConstantStringSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("qazxswedc")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("Constant string supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def testSingleLongParamSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("_%d[0-1000]")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("Single long parameter supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def testSingleDoubleParamSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("_%f{#.##}[0-1000]")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("Single double parameter supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def testSingleDateParamSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("_%D{yyyy-MM-dd'T'HH:mm:ss.SSS}")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("Single date parameter supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def testMixedParamsSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("_%d[0-1000]_%f{#.##}[0-1000]_%D{yyyy-MM-dd'T'HH:mm:ss.SSS}")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("Mixed parameters supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def test10LongParamsSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier(
			"_%d[0-10]_%d[10-100]_%d[10-1000]_%d[1000-10000]_%d[10000-100000]_%d[100000-1000000]_%d[1000000-10000000]_%d[10000000-100000000]_%d[100000000-1000000000]_%d[1000000000-2000000000]"
		)
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("10 long parameters supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def test10DoubleParamsSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("_%f{#.###}[0-10]_%f{#.###}[10-100]_%f{#.###}[10-1000]_%f{#.###}[1000-10000]_%f{#.###}[10000-100000]_%f{#.###}[100000-1000000]_%f{#.###}[1000000-10000000]_%f{#.###}[10000000-100000000]_%f{#.###}[100000000-1000000000]_%f{#.###}[1000000000-2000000000]")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("10 double parameters supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
	
	@Test @throws[Exception]
	def test10DateParamsSupplyRate(): Unit = {
		val input = new RangePatternDefinedSupplier("_%D{yyyy-MM-dd'T'HH:mm:ss.SSS}_%D{yyyy-MM-dd'T'HH:mm:ss.SSS}_%D{yyyy-'W'ww}_%D{yyMMddHHmmssZ}_%D{EEE, d MMM yyyy HH:mm:ss Z}_%D{yyyyy.MMMMM.dd GGG hh:mm aaa}_%D{K:mm a, z}_%D{h:mm a}_%D{EEE, MMM d, ''yy}_%D{yyyy.MM.dd G 'at' HH:mm:ss z}")
		val counter = new LongAdder
		val buff = new util.ArrayList[String](BATCH_SIZE)
		val t = new Thread(() => {
			while(true) {
				Assert.assertEquals(BATCH_SIZE, input.get(buff, BATCH_SIZE))
				counter.add(BATCH_SIZE)
				buff.clear()
			}
		})
		t.start()
		TimeUnit.SECONDS.timedJoin(t, TIME_LIMIT_SEC)
		t.interrupt()
		System.out.println("10 date parameters supply rate: " + counter.sum / TIME_LIMIT_SEC)
	}
}
