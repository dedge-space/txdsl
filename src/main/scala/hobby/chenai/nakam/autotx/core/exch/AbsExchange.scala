/*
 * Copyright (C) 2017-present, Chenai Nakam(chenai.nakam@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hobby.chenai.nakam.autotx.core.exch

import java.util.concurrent.ConcurrentHashMap
import hobby.chenai.nakam.autotx.coinTpeImpl
import hobby.chenai.nakam.autotx.core.coin._
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.chenai.nakam.tool.cache.{Delegate, LazyGet, Lru, Memoize}

import scala.language.postfixOps

/**
  * @param name         交易所名称。
  * @param pricingToken 定价的token。必须填，但只要不`updateTokenPricingRate()`就不会用它来计算定价。
  * @param pricingCash  定价的法币。
  * @param tokens       本平台支持的tokens。
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 30/05/2017
  */
abstract class AbsExchange(val name: String, override val pricingToken: AbsTokenGroup, override val pricingCash: AbsCashGroup,
                           tokens: AbsTokenGroup*) extends CoinEx(pricingToken, pricingCash)
  with Memoize[(AbsTokenGroup, AbsCoinGroup), FixedFracDigitsRule] with LazyGet with Lru {
  require(!tokens.contains(pricingToken))

  val supportTokens = pricingToken :: tokens.toList.distinct
  private val zero = pricingCash.unitStd * 0
  supportTokens.foreach(cashPriRateMap.put(_, zero))
  // tokenPriRateMap（即token定价）是可选的，因此不put into Map.
  // supportTokens.foreach(tokenPriRateMap.put(_, zero))

  println("init:" + toString)

  /** 不同交易所的规则不同。所以需要重写。 */
  protected def loadFfdRule(counterParty: (AbsTokenGroup, AbsCoinGroup)): FixedFracDigitsRule

  override protected val maxCacheSize = 50
  override protected val delegate = new Delegate[(AbsTokenGroup, AbsCoinGroup), FixedFracDigitsRule] {
    override def load(key: (AbsTokenGroup, AbsCoinGroup)) = Option(loadFfdRule(key))

    override def update(key: (AbsTokenGroup, AbsCoinGroup), value: FixedFracDigitsRule) = Option(value)
  }

  override final def getFfdRule[T <: AbsTokenGroup, C <: AbsCoinGroup](token: T, pricingCoin: C): FixedFracDigitsRule =
    get(token, pricingCoin).as[FixedFracDigitsRule]

  // 没有比特币汇率则必须有法币汇率
  /** 从加密货币到法币的汇率，而法币在一个交易所只有一种。 */
  private final lazy val cashPriRateMap = new ConcurrentHashMap[AbsTokenGroup, PriCCoin]
  /** 从加密货币到比特币的汇率。 */
  private final lazy val tokenPriRateMap = new ConcurrentHashMap[AbsTokenGroup, PriTCoin]

  override protected final def isCashExSupported(token: AbsTokenGroup) = cashPriRateMap.containsKey(token)

  override protected final def isTokenExSupported(token: AbsTokenGroup) = tokenPriRateMap.containsKey(token)

  protected final lazy val impl = coinTpeImpl[PriTCoin, PriCCoin]

  final def updateCashPricingRate(token: AbsTokenGroup#Unt, rate: AbsCashGroup#AbsCash): Unit = {
    import impl._
    cashPriRateMap.put(requireSupports(token.group), rate)
  }

  final def updateTokenPricingRate(token: AbsTokenGroup#Unt, rate: AbsTokenGroup#AbsToken): Unit = {
    import impl._
    tokenPriRateMap.put(requireSupports(token.group), rate)
  }

  private def requireSupports(tokenGroup: AbsTokenGroup): AbsTokenGroup = {
    require(supportTokens.contains(tokenGroup), s"parameter `tokenGroup`: $tokenGroup, " +
      s"is not contains in `supportTokens`: $supportTokenString.")
    tokenGroup
  }

  override protected final def getExRate(tokenGroup: AbsTokenGroup, token$cash: Boolean) = {
    val rate = if (token$cash) tokenPriRateMap.get(tokenGroup) else cashPriRateMap.get(tokenGroup)
    require(rate != null && rate.value > 0, s"rate of $tokenGroup(:${
      if (token$cash) pricingToken else pricingCash
    }) have not initialized on $name.")
    rate
  }

  override def toString = s"$name(:$pricingCash|$pricingToken)$supportTokenString"

  def supportTokenString = supportTokens.mkString("[", ", ", "]")
}

abstract class CoinEx(val pricingToken: AbsTokenGroup, val pricingCash: AbsCashGroup) {
  type PriTCoin = pricingToken.COIN
  type PriCCoin = pricingCash.COIN

  protected val supportTokens: Seq[AbsCoinGroup]

  protected def getFfdRule[T <: AbsTokenGroup, C <: AbsCoinGroup](token: T, pricingCoin: C): FixedFracDigitsRule

  protected def isTokenExSupported(token: AbsTokenGroup): Boolean

  protected def isCashExSupported(token: AbsTokenGroup): Boolean

  protected def getExRate(tokenGroup: AbsTokenGroup, token$cash: Boolean): AbsCoinGroup#AbsCoin

  def applyExch(src: AbsCoinGroup#AbsCoin, dst: AbsCoinGroup#Unt): AbsCoinGroup#AbsCoin = {
    // pricingToken已经在supportTokens里面了
    if ((if (src.isCash) src.group eq pricingCash else supportTokens.contains(src.group.as[AbsTokenGroup]))
      && (if (dst.isCash) dst.group eq pricingCash else supportTokens.contains(dst.group.as[AbsTokenGroup])))
      ex.applyOrElse((src, dst, true), (x: (AbsCoinGroup#AbsCoin, _, _)) => x._1)
    else src
  }

  /**
    * 有token(btc)定价则优先token, 否则强制法币定价（若没有则报错）。
    */
  private lazy val ex: (AbsCoinGroup#AbsCoin, AbsCoinGroup#AbsCoin, Boolean) PartialFunction AbsCoinGroup#AbsCoin = {
    // pricingCash => pricingCash // 到这里不会出现两个不一样的法币
    case (cash: AbsCashGroup#AbsCash, dst: AbsCashGroup#AbsCash, _) => dst.unit << cash
    // token => pricingCash
    case (token: AbsTokenGroup#AbsToken, dst: AbsCashGroup#AbsCash, promise) =>
      if (promise /*注意这个promise不能把任务再转给pricingToken，不然会死递归*/ || isCashExSupported(token.group)) {
        val ffdRule = getFfdRule(token.group, dst.group)
        import ffdRule._
        import ffdRule.impl._
        dst.unit << sell(token, getExRate(token.group, token$cash = false))
      } else token
    // pricingCash => token
    case (cash: AbsCashGroup#AbsCash, dst: AbsTokenGroup#AbsToken, promise) =>
      if (promise || isCashExSupported(dst.group)) {
        val ffdRule = getFfdRule(dst.group, cash.group)
        import ffdRule._
        import ffdRule.impl._
        dst.unit << buy(cash /*注意这里不是dst, cash表示有多少钱*/ , getExRate(dst.group, token$cash = false))
      } else cash
    // pricingToken => pricingToken // 与token不同，cash就一种，不需要判断。
    case (token: AbsTokenGroup#AbsToken, dst: AbsTokenGroup#AbsToken, _)
      if (token.group eq pricingToken) && (dst.group eq pricingToken) => dst.unit << token
    // token => pricingToken
    case (token: AbsTokenGroup#AbsToken, dst: AbsTokenGroup#AbsToken, promise) if dst.group eq pricingToken =>
      if (isTokenExSupported(token.group)) {
        val ffdRule = getFfdRule(token.group, dst.group)
        import ffdRule._
        import ffdRule.impl._
        dst.unit << sell(token, getExRate(token.group, token$cash = true))
      } else if (promise) ex.apply(ex.apply(token, pricingCash.unitStd, promise), dst, promise)
      else token
    // pricingToken => token
    case (token: AbsTokenGroup#AbsToken, dst: AbsTokenGroup#AbsToken, promise) if token.group eq pricingToken =>
      if (isTokenExSupported(dst.group)) {
        val ffdRule = getFfdRule(dst.group, token.group)
        import ffdRule._
        import ffdRule.impl._
        dst.unit << buy(token /*注意这里不是dst, token表示有多少钱*/ , getExRate(dst.group, token$cash = true))
      } else if (promise) ex.apply(ex.apply(token, pricingCash.unitStd, promise), dst, promise)
      else token
    // token => token
    case (src: AbsTokenGroup#AbsToken, dst: AbsTokenGroup#AbsToken, promise) =>
      if (dst.group == src.group) dst.unit << src
      else {
        val ptf = isTokenExSupported(src.group)
        val ptt = isTokenExSupported(dst.group)
        val cf = isCashExSupported(src.group)
        val ct = isCashExSupported(dst.group)
        if (ptf && ptt) ex.apply(ex.apply(src, pricingToken.unitStd, false), dst, false)
        else if (cf && ct) ex.apply(ex.apply(src, pricingCash.unitStd, false), dst, false)
        else if (promise) {
          if (ptf || ptt) ex.apply(ex.apply(src, pricingToken.unitStd, true), dst, true)
          else ex.apply(ex.apply(src, pricingCash.unitStd, true), dst, true)
        } else src
      }
  }
}
