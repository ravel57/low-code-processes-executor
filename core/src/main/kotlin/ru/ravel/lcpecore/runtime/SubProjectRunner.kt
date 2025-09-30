package ru.ravel.lcpecore.runtime

import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject

interface SubProjectRunner {

	/** Выполнить подпроект блока SUB_PROJECT и вернуть список карт на выходы внешнего блока */
	fun run(parentBlock: CoreBlock, outerProject: CoreProject): List<MutableMap<String, Any?>>

}