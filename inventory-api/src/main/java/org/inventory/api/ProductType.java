package org.inventory.api;

/**
 * 旅游产品类型。
 * <p>同业交易平台目前覆盖：</p>
 * <ul>
 *   <li>{@link #FLIGHT} —— 国内/国际机票（按航段+航班号+起飞日期为粒度的舱位余量）</li>
 *   <li>{@link #HOTEL}  —— 酒店（按酒店+房型+入住日期为粒度的房间余量）</li>
 * </ul>
 * 后续可扩展 TRAIN / VISA / TRANSFER / TICKET 等。
 */
public enum ProductType {
    FLIGHT,
    HOTEL
}
