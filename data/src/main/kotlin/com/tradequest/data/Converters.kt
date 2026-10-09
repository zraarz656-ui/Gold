package com.tradequest.data

import androidx.room.TypeConverter
import com.tradequest.engine.Impact
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side

/** Enum <-> TEXT converters for the Room entities. */
class Converters {
    @TypeConverter fun sideToString(v: Side): String = v.name
    @TypeConverter fun stringToSide(v: String): Side = Side.valueOf(v)

    @TypeConverter fun orderTypeToString(v: OrderType): String = v.name
    @TypeConverter fun stringToOrderType(v: String): OrderType = OrderType.valueOf(v)

    @TypeConverter fun impactToString(v: Impact): String = v.name
    @TypeConverter fun stringToImpact(v: String): Impact = Impact.valueOf(v)

    @TypeConverter fun seasonStatusToString(v: SeasonStatus): String = v.name
    @TypeConverter fun stringToSeasonStatus(v: String): SeasonStatus = SeasonStatus.valueOf(v)

    @TypeConverter fun orderStatusToString(v: OrderStatus): String = v.name
    @TypeConverter fun stringToOrderStatus(v: String): OrderStatus = OrderStatus.valueOf(v)
}
