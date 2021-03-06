/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.compress;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.lops.MMTSJ.MMTSJType;
import org.apache.sysml.lops.MapMultChain.ChainType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.compress.ColGroup.CompressionType;
import org.apache.sysml.runtime.compress.estim.CompressedSizeEstimator;
import org.apache.sysml.runtime.compress.estim.CompressedSizeInfo;
import org.apache.sysml.runtime.compress.estim.SizeEstimatorFactory;
import org.apache.sysml.runtime.compress.utils.ConverterUtils;
import org.apache.sysml.runtime.compress.utils.LinearAlgebraUtils;
import org.apache.sysml.runtime.controlprogram.parfor.stat.Timing;
import org.apache.sysml.runtime.functionobjects.KahanPlus;
import org.apache.sysml.runtime.functionobjects.KahanPlusSq;
import org.apache.sysml.runtime.functionobjects.Multiply;
import org.apache.sysml.runtime.functionobjects.ReduceRow;
import org.apache.sysml.runtime.instructions.cp.KahanObject;
import org.apache.sysml.runtime.matrix.data.LibMatrixBincell;
import org.apache.sysml.runtime.matrix.data.LibMatrixReorg;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.MatrixValue;
import org.apache.sysml.runtime.matrix.data.SparseBlock;
import org.apache.sysml.runtime.matrix.operators.AggregateBinaryOperator;
import org.apache.sysml.runtime.matrix.operators.AggregateUnaryOperator;
import org.apache.sysml.runtime.matrix.operators.BinaryOperator;
import org.apache.sysml.runtime.matrix.operators.ScalarOperator;

/**
 * Experimental version of MatrixBlock that allows a compressed internal
 * representation.
 */
public class CompressedMatrixBlock extends MatrixBlock implements Externalizable
{
	private static final long serialVersionUID = 7319972089143154057L;
	
	//internal configuration
	public static final int MAX_NUMBER_COCODING_COLUMNS = 1000;
	public static final double MIN_COMPRESSION_RATIO = 2.0;
	public static final double MIN_RLE_RATIO = 1.0; // Minimum additional compression (non-RLE size / RLE size) before we switch to run-length encoding.
	public static final boolean TRANSPOSE_INPUT = true;
	public static final boolean MATERIALIZE_ZEROS = false;
	public static final long MIN_PAR_AGG_THRESHOLD = 16*1024*1024; //16MB
	public static final boolean INVESTIGATE_ESTIMATES = false;
	private static final boolean LDEBUG = false; //local debug flag
	
	private static final Log LOG = LogFactory.getLog(CompressedMatrixBlock.class.getName());
	
	static{
		// for internal debugging only
		if( LDEBUG ) {
			Logger.getLogger("org.apache.sysml.runtime.compress")
				  .setLevel((Level) Level.DEBUG);
		}	
	}
	
	protected ArrayList<ColGroup> _colGroups = null;
	protected CompressionStatistics _stats = null;
	
	public CompressedMatrixBlock() {
		super(-1, -1, true);
	}
	
	/**
	 * Main constructor for building a block from scratch.
	 * 
	 * @param rl
	 *            number of rows in the block
	 * @param cl
	 *            number of columns
	 * @param sparse
	 *            true if the UNCOMPRESSED representation of the block should be
	 *            sparse
	 */
	public CompressedMatrixBlock(int rl, int cl, boolean sparse) {
		super(rl, cl, sparse);
	}

	/**
	 * "Copy" constructor to populate this compressed block with the
	 * uncompressed contents of a conventional block. Does <b>not</b> compress
	 * the block.
	 */
	public CompressedMatrixBlock(MatrixBlock mb) {
		super(mb.getNumRows(), mb.getNumColumns(), mb.isInSparseFormat());
		
		//shallow copy (deep copy on compression, prevents unnecessary copy) 
		if( isInSparseFormat() )
			sparseBlock = mb.getSparseBlock();
		else
			denseBlock = mb.getDenseBlock();
		nonZeros = mb.getNonZeros();
	}

	/**
	 * 
	 * @return the column groups constructed by the compression process.
	 * 
	 */
	public ArrayList<ColGroup> getColGroups() {
		return _colGroups;
	}

	/**
	 * @return true if this block is in compressed form; false if the block has
	 *         not yet been compressed
	 */
	public boolean isCompressed() {
		return (_colGroups != null);
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean isSingleUncompressedGroup(){
		return (_colGroups!=null && _colGroups.size()==1 
				&& _colGroups.get(0) instanceof ColGroupUncompressed);
	}

	private void allocateColGroupList() {
		_colGroups = new ArrayList<ColGroup>();
	}
	
	@Override
	public boolean isEmptyBlock(boolean safe)  {
		if( !isCompressed() )
			return super.isEmptyBlock(safe);		
		return (_colGroups == null || getNonZeros()==0);
	}
	
	/**
	 * Compress the contents of this matrix block. After compression, the
	 * uncompressed data is discarded. Attempts to update this block after
	 * calling this method currently result in INCORRECT BEHAVIOR, something
	 * which should be fixed if we move ahead with this compression strategy.
	 * 
	 * +per column sparsity
	 */
	public void compress() 
		throws DMLRuntimeException 
	{
		//check for redundant compression
		if( isCompressed() ){
			throw new DMLRuntimeException("Redundant compression, block already compressed.");
		}

		Timing time = new Timing(true);
		_stats = new CompressionStatistics();
		
		// SAMPLE-BASED DECISIONS:
		// Decisions such as testing if a column is amenable to bitmap
		// compression or evaluating co-coding potentionls are made based on a
		// subset of the rows. For large datasets, sampling might take a
		// significant amount of time. So, we generate only one sample and use
		// it for the entire compression process.

		//prepare basic meta data and deep copy / transpose input
		final int numRows = getNumRows();
		final int numCols = getNumColumns();
		final boolean sparse = isInSparseFormat();
		MatrixBlock rawblock = this;
		if( TRANSPOSE_INPUT )
			rawblock = LibMatrixReorg.transpose(rawblock, new MatrixBlock(numCols, numRows, sparse));
		else
			rawblock = new MatrixBlock(this);
		
		//construct sample-based size estimator
		CompressedSizeEstimator bitmapSizeEstimator = 
				SizeEstimatorFactory.getSizeEstimator(rawblock, numRows);

		//allocate list of column groups
		allocateColGroupList();

		// The current implementation of this method is written for correctness,
		// not for performance or for minimal use of temporary space.

		// We start with a full set of columns.
		HashSet<Integer> remainingCols = new HashSet<Integer>();
		for (int i = 0; i < numCols; i++) {
			remainingCols.add(i);
		}

		// PHASE 1: Classify columns by compression type
		// We start by determining which columns are amenable to bitmap
		// compression

		// It is correct to use the dense size as the uncompressed size
		// FIXME not numRows but nnz / col otherwise too aggressive overestimation
		// of uncompressed size and hence overestimation of compression potential
		double uncompressedColumnSize = 8 * numRows;

		// information about the bitmap amenable columns
		List<Integer> bitmapCols = new ArrayList<Integer>();
		List<Integer> uncompressedCols = new ArrayList<Integer>();
		List<Integer> colsCardinalities = new ArrayList<Integer>();
		List<Long> compressedSizes = new ArrayList<Long>();
		HashMap<Integer, Double> compressionRatios = new HashMap<Integer, Double>();
		
		// Minimum ratio (size of uncompressed / size of compressed) that we
		// will accept when encoding a field with a bitmap.
		for (int col = 0; col < numCols; col++) 
		{
			CompressedSizeInfo compressedSizeInfo = bitmapSizeEstimator
					.estimateCompressedColGroupSize(new int[] { col });
			long compressedSize = compressedSizeInfo.getMinSize();
			double compRatio = uncompressedColumnSize / compressedSize;
			
			//FIXME: compression ratio should be checked against 1 instead of min compression
			//ratio; I think this threshold was only required because we overestimated the 
			//the uncompressed column size with n\alpha instead of z\alpha
			if (compRatio >= MIN_COMPRESSION_RATIO) {
				bitmapCols.add(col);
				compressionRatios.put(col, compRatio);
				colsCardinalities.add(compressedSizeInfo.getEstCarinality());
				compressedSizes.add(compressedSize);
			}
			else
				uncompressedCols.add(col);
		}

		_stats.timePhase1 = time.stop();
		if( LOG.isDebugEnabled() )
			LOG.debug("compression phase 1: "+_stats.timePhase1);
		
		// Filters for additional types of compression should be inserted here.

		// PHASE 2: Grouping columns
		// Divide the bitmap columns into column groups.
		List<int[]> bitmapColGrps = null;
		if (bitmapCols.size() > MAX_NUMBER_COCODING_COLUMNS) {
			// Too many columns to compute co-coding groups with current methods.
			// Generate singleton groups.
			bitmapColGrps = new ArrayList<int[]>(bitmapCols.size());
			for (int col : bitmapCols) {
				bitmapColGrps.add(new int[] { col });
			}
		} 
		else {
			bitmapColGrps = PlanningCoCoder.findCocodesByPartitioning(
					bitmapSizeEstimator, bitmapCols, colsCardinalities,
					compressedSizes, numRows, isInSparseFormat() ? 
					OptimizerUtils.getSparsity(numRows, numCols, getNonZeros()): 1);
		}

		_stats.timePhase2 = time.stop();
		if( LOG.isDebugEnabled() )
			LOG.debug("compression phase 2: "+_stats.timePhase2);
		
		if( INVESTIGATE_ESTIMATES ) {
			double est = 0;
			for( int[] groupIndices : bitmapColGrps )
				est += bitmapSizeEstimator.estimateCompressedColGroupSize(groupIndices).getMinSize();
			est += uncompressedCols.size() * uncompressedColumnSize;
			_stats.estSize = est;
		}
		
		// PHASE 3: Compress and correct sample-based decisions
		
		for (int[] groupIndices : bitmapColGrps) 
		{
			int[] allGroupIndices = null;
			int allColsCount = groupIndices.length;
			CompressedSizeInfo bitmapSizeInfo;
			// The compression type is decided based on a full bitmap since it
			// will be reused for the actual compression step.
			UncompressedBitmap ubm;
			PriorityQueue<CompressedColumn> compRatioPQ = null;
			boolean skipGroup = false;
			while (true) 
			{
				ubm = BitmapEncoder.extractBitmap(groupIndices, rawblock); 
				bitmapSizeInfo = bitmapSizeEstimator
						.estimateCompressedColGroupSize(ubm);
				double compRatio = uncompressedColumnSize * groupIndices.length
						/ bitmapSizeInfo.getMinSize();
				if (compRatio >= MIN_COMPRESSION_RATIO) {
					// we have a good group
					for( Integer col : groupIndices )
						remainingCols.remove(col);
					break;
				} else {
					// modify the group
					if (compRatioPQ == null) {
						// first modification
						allGroupIndices = Arrays.copyOf(groupIndices, groupIndices.length);
						compRatioPQ = new PriorityQueue<CompressedMatrixBlock.CompressedColumn>();
						for (int i = 0; i < groupIndices.length; i++)
							compRatioPQ.add(new CompressedColumn(i,
									compressionRatios.get(groupIndices[i])));
					}

					// index in allGroupIndices
					int removeIx = compRatioPQ.poll().colIx;
					allGroupIndices[removeIx] = -1;
					allColsCount--;
					if (allColsCount == 0) {
						skipGroup = true;
						break;
					}
					groupIndices = new int[allColsCount];
					// copying the values that do not equal -1
					int ix = 0;
					for (int col : allGroupIndices) {
						if (col != -1) {
							groupIndices[ix++] = col;
						}
					}

				}
			}

			if (skipGroup)
				continue;
			long rleNumBytes = bitmapSizeInfo.getRLESize();
			long offsetNumBytes = bitmapSizeInfo.getOLESize();
			double rleRatio = (double) offsetNumBytes / (double) rleNumBytes;

			if (rleRatio > MIN_RLE_RATIO) {
				ColGroupRLE compressedGroup = new ColGroupRLE(groupIndices,
						numRows, ubm);
				_colGroups.add(compressedGroup);
			} 
			else {
				ColGroupOLE compressedGroup = new ColGroupOLE(
						groupIndices, numRows, ubm);
				_colGroups.add(compressedGroup);
			}

		}
		
		_stats.timePhase3 = time.stop();
		if( LOG.isDebugEnabled() )
			LOG.debug("compression phase 3: "+_stats.timePhase3);
		
		// Phase 4: Cleanup
		// The remaining columns are stored uncompressed as one big column group
		if (remainingCols.size() > 0) {
			ArrayList<Integer> list = new ArrayList<Integer>(remainingCols);
			ColGroupUncompressed ucgroup = new ColGroupUncompressed(list, rawblock);
			_colGroups.add(ucgroup);
		}

		//final cleanup (discard uncompressed block)
		rawblock.cleanupBlock(true, true);
		this.cleanupBlock(true, true);
		
		_stats.timePhase4 = time.stop();
		if( LOG.isDebugEnabled() )
			LOG.debug("compression phase 4: "+_stats.timePhase4);
	}

	/**
	 * @return a new uncompressed matrix block containing the contents of this
	 *         block
	 */
	public MatrixBlock decompress() throws DMLRuntimeException 
	{
		//early abort for not yet compressed blocks
		if( !isCompressed() )
			return new MatrixBlock(this); 
		
		//preallocation sparse rows to avoid repeated reallocations		
		MatrixBlock ret = new MatrixBlock(getNumRows(), getNumColumns(), isInSparseFormat(), getNonZeros());
		if( ret.isInSparseFormat() ) {
			int[] rnnz = new int[rlen];
			for (ColGroup grp : _colGroups)
				grp.countNonZerosPerRow(rnnz);
			ret.allocateSparseRowsBlock();
			SparseBlock rows = ret.getSparseBlock();
			for( int i=0; i<rlen; i++ )
				rows.allocate(i, rnnz[i]);
		}
		
		//core decompression (append if sparse)
		for (ColGroup grp : _colGroups)
			grp.decompressToBlock(ret);
		
		//post-processing (for append in decompress)
		if( isInSparseFormat() )
			ret.sortSparseRows();

		return ret;
	}

	public CompressionStatistics getCompressionStatistics(){
		return _stats;
	}

	/**
	 * 
	 * @return an upper bound on the memory used to store this compressed block
	 *         considering class overhead.
	 */
	public long estimateCompressedSizeInMemory() {
		if (!isCompressed())
			return 0;
		// basic data inherited from MatrixBlock
		long total = MatrixBlock.estimateSizeInMemory(0, 0, 0);
		// adding the size of colGroups ArrayList overhead
		// object overhead (32B) + int size (4B) + int modCount (4B) + Object[]
		// elementData overhead + reference (32+8)B +reference ofr each Object (8B)
		total += 80 + 8 * _colGroups.size();
		for (ColGroup grp : _colGroups)
			total += grp.estimateInMemorySize();
		return total;
	}

	private class CompressedColumn implements Comparable<CompressedColumn> {
		int colIx;
		double compRatio;

		public CompressedColumn(int colIx, double compRatio) {
			this.colIx = colIx;
			this.compRatio = compRatio;
		}

		@Override
		public int compareTo(CompressedColumn o) {
			return (int) Math.signum(compRatio - o.compRatio);
		}
	}
	
	public static class CompressionStatistics {
		public double timePhase1 = -1;
		public double timePhase2 = -1;
		public double timePhase3 = -1;
		public double timePhase4 = -1;
		public double estSize = -1;
		
		public CompressionStatistics() {
			//do nothing
		}
		
		public CompressionStatistics(double t1, double t2, double t3, double t4){
			timePhase1 = t1;
			timePhase2 = t2;
			timePhase3 = t3;
			timePhase4 = t4;
		}
	} 

	
	//////////////////////////////////////////
	// Serialization / Deserialization

	@Override
	public long getExactSizeOnDisk() 
	{
		//header information
		long ret = 12;
		
		for( ColGroup grp : _colGroups ) {
			ret += 1; //type info
			ret += grp.getExactSizeOnDisk();
		}
		
		return ret;
	}
	
	@Override
	public void readFields(DataInput in) 
		throws IOException 
	{
		boolean compressed = in.readBoolean();
		
		//deserialize uncompressed block
		if( !compressed ) {
			super.readFields(in);
			return;
		}
		
		//deserialize compressed block
		rlen = in.readInt();
		clen = in.readInt();
		nonZeros = in.readLong();
		int ncolGroups = in.readInt();
		
		_colGroups = new ArrayList<ColGroup>(ncolGroups);
		for( int i=0; i<ncolGroups; i++ ) 
		{
			CompressionType ctype = CompressionType.values()[in.readByte()];
			ColGroup grp = null;
			
			//create instance of column group
			switch( ctype ) {
				case UNCOMPRESSED:
					grp = new ColGroupUncompressed(); break;
				case OLE_BITMAP:
					grp = new ColGroupOLE(); break;
				case RLE_BITMAP:
					grp = new ColGroupRLE(); break;
			}
			
			//deserialize and add column group
			grp.readFields(in);
			_colGroups.add(grp);
		}
	}
	
	@Override
	public void write(DataOutput out) 
		throws IOException 
	{
		out.writeBoolean( isCompressed() );
		
		//serialize uncompressed block
		if( !isCompressed() ) {
			super.write(out);
			return;
		}
		
		//serialize compressed matrix block
		out.writeInt(rlen);
		out.writeInt(clen);
		out.writeLong(nonZeros);
		out.writeInt(_colGroups.size());
		
		for( ColGroup grp : _colGroups ) {
			out.writeByte( grp.getCompType().ordinal() );
			grp.write(out); //delegate serialization
		}
	}
	
	
	/**
	 * Redirects the default java serialization via externalizable to our default 
	 * hadoop writable serialization for efficient broadcast/rdd deserialization. 
	 * 
	 * @param is
	 * @throws IOException
	 */
	@Override
	public void readExternal(ObjectInput is) 
		throws IOException
	{
		readFields(is);
	}
	
	/**
	 * Redirects the default java serialization via externalizable to our default 
	 * hadoop writable serialization for efficient broadcast/rdd serialization. 
	 * 
	 * @param is
	 * @throws IOException
	 */
	@Override
	public void writeExternal(ObjectOutput os) 
		throws IOException
	{
		write(os);	
	}
	
	
	//////////////////////////////////////////
	// Operations (overwrite existing ops for seamless integration)

	@Override
	public MatrixValue scalarOperations(ScalarOperator sop, MatrixValue result) 
		throws DMLRuntimeException
	{
		//call uncompressed matrix scalar if necessary
		if( !isCompressed() ) {
			return super.scalarOperations(sop, result);
		}
		
		//allocate the output matrix block
		CompressedMatrixBlock ret = null;
		if( result==null || !(result instanceof CompressedMatrixBlock) )
			ret = new CompressedMatrixBlock(getNumRows(), getNumColumns(), sparse);
		else {
			ret = (CompressedMatrixBlock) result;
			ret.reset(rlen, clen);
		}
		
		// Apply the operation recursively to each of the column groups.
		// Most implementations will only modify metadata.
		ArrayList<ColGroup> newColGroups = new ArrayList<ColGroup>();
		for (ColGroup grp : _colGroups) {
			newColGroups.add(grp.scalarOperation(sop));
		}
		ret._colGroups = newColGroups;
		ret.setNonZeros(rlen*clen);
		
		return ret;
	}

	@Override
	public MatrixBlock appendOperations(MatrixBlock that, MatrixBlock ret) 
		throws DMLRuntimeException
	{
		//call uncompressed matrix append if necessary
		if( !isCompressed() ) {
			if( that instanceof CompressedMatrixBlock )
				that = ((CompressedMatrixBlock) that).decompress();
			return super.appendOperations(that, ret);
		}
		
		final int m = rlen;
		final int n = clen+that.getNumColumns();
		final long nnz = nonZeros+that.getNonZeros();		
		
		//init result matrix 
		CompressedMatrixBlock ret2 = null;
		if( ret == null || !(ret instanceof CompressedMatrixBlock) ) {
			ret2 = new CompressedMatrixBlock(m, n, isInSparseFormat());
		}
		else {
			ret2 = (CompressedMatrixBlock) ret;
			ret2.reset(m, n);
		}
			
		//shallow copy of lhs column groups
		ret2.allocateColGroupList();
		ret2._colGroups.addAll(_colGroups);
		
		//copy of rhs column groups w/ col index shifting
		if( !(that instanceof CompressedMatrixBlock) ) {
			that = new CompressedMatrixBlock(that);
			((CompressedMatrixBlock)that).compress();
		}
		ArrayList<ColGroup> inColGroups = ((CompressedMatrixBlock) that)._colGroups;
		for( ColGroup group : inColGroups ) {
			ColGroup tmp = ConverterUtils.copyColGroup(group);
			tmp.shiftColIndices(clen);
			ret2._colGroups.add(tmp);
		}
		
		//meta data maintenance
		ret2.setNonZeros(nnz);		
		return ret2;
	}
	
	@Override
	public MatrixBlock chainMatrixMultOperations(MatrixBlock v, MatrixBlock w, MatrixBlock out, ChainType ctype) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.chainMatrixMultOperations(v, w, out, ctype);
		}
		
		//single-threaded mmchain of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().chainMatrixMultOperations(v, w, out, ctype);
		}
		
		//Timing time = new Timing(true);
		
		//prepare result
		if( out != null )
			out.reset(clen, 1, false);
		else 
			out = new MatrixBlock(clen, 1, false);
		
		//empty block handling
		if( isEmptyBlock(false) ) 
			return out;
			
		//compute matrix mult
		MatrixBlock tmp = new MatrixBlock(rlen, 1, false);
		rightMultByVector(v, tmp);
		if( ctype == ChainType.XtwXv ) {
			BinaryOperator bop = new BinaryOperator(Multiply.getMultiplyFnObject());
			LibMatrixBincell.bincellOpInPlace(tmp, w, bop);
		}
		leftMultByVectorTranspose(_colGroups, tmp, out, true);
		
		//System.out.println("Compressed MMChain in "+time.stop());
		
		return out;
	}

	@Override
	public MatrixBlock chainMatrixMultOperations(MatrixBlock v, MatrixBlock w, MatrixBlock out, ChainType ctype, int k) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ){
			return super.chainMatrixMultOperations(v, w, out, ctype, k);
		}
		
		//multi-threaded mmchain of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().chainMatrixMultOperations(v, w, out, ctype, k);
		}

		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;
		
		//prepare result
		if( out != null )
			out.reset(clen, 1, false);
		else 
			out = new MatrixBlock(clen, 1, false);
		
		//empty block handling
		if( isEmptyBlock(false) ) 
			return out;
		
		//compute matrix mult
		MatrixBlock tmp = new MatrixBlock(rlen, 1, false);
		rightMultByVector(v, tmp, k);
		if( ctype == ChainType.XtwXv ) {
			BinaryOperator bop = new BinaryOperator(Multiply.getMultiplyFnObject());
			LibMatrixBincell.bincellOpInPlace(tmp, w, bop);
		}
		leftMultByVectorTranspose(_colGroups, tmp, out, true, k);
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed MMChain k="+k+" in "+time.stop());
		
		return out;
	}
	
	@Override
	public MatrixValue aggregateBinaryOperations(MatrixValue mv1, MatrixValue mv2, MatrixValue result, AggregateBinaryOperator op)
			throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.aggregateBinaryOperations(mv1, mv2, result, op);
		}
	
		//multi-threaded mm of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			MatrixBlock tmp = ((ColGroupUncompressed)_colGroups.get(0)).getData();
			return tmp.aggregateBinaryOperations(this==mv1?tmp:mv1, this==mv2?tmp:mv2, result, op);
		}
		
		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;
		
		//setup meta data (dimensions, sparsity)
		int rl = mv1.getNumRows();
		int cl = mv2.getNumColumns();
		
		//create output matrix block
		MatrixBlock ret = (MatrixBlock) result;
		if( ret==null )
			ret = new MatrixBlock(rl, cl, false, rl*cl);
		else
			ret.reset(rl, cl, false, rl*cl);
		
		//compute matrix mult
		if( mv1.getNumRows()>1 && mv2.getNumColumns()==1 ) { //MV right
			CompressedMatrixBlock cmb = (CompressedMatrixBlock)mv1;
			MatrixBlock mb = (MatrixBlock) mv2;
			if( op.getNumThreads()>1 )
				cmb.rightMultByVector(mb, ret, op.getNumThreads());
			else
				cmb.rightMultByVector(mb, ret);
		}
		else if( mv1.getNumRows()==1 && mv2.getNumColumns()>1 ) { //MV left
			MatrixBlock mb = (MatrixBlock) mv1;
			if( op.getNumThreads()>1 )
				leftMultByVectorTranspose(_colGroups, mb, ret, false, op.getNumThreads());
			else
				leftMultByVectorTranspose(_colGroups, mb, ret, false);
		}
		else {
			//NOTE: we could decompress and invoke super.aggregateBinary but for now
			//we want to have an eager fail if this happens
			throw new DMLRuntimeException("Unsupported matrix-matrix multiplication over compressed matrix block.");
		}
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed MM in "+time.stop());
		
		return ret;
	}
	
	@Override
	public MatrixValue aggregateUnaryOperations(AggregateUnaryOperator op, MatrixValue result, 
			int blockingFactorRow, int blockingFactorCol, MatrixIndexes indexesIn, boolean inCP) 
		throws DMLRuntimeException
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.aggregateUnaryOperations(op, result, blockingFactorRow, blockingFactorCol, indexesIn, inCP);
		}
		
		//check for supported operations
		if( !(op.aggOp.increOp.fn instanceof KahanPlus || op.aggOp.increOp.fn instanceof KahanPlusSq) ){
			throw new DMLRuntimeException("Unary aggregates other than sums not supported yet.");
		}
		
		//prepare output dimensions
		CellIndex tempCellIndex = new CellIndex(-1,-1);
		op.indexFn.computeDimension(rlen, clen, tempCellIndex);
		if(op.aggOp.correctionExists) {
			switch(op.aggOp.correctionLocation)
			{
				case LASTROW: tempCellIndex.row++;  break;
				case LASTCOLUMN: tempCellIndex.column++; break;
				case LASTTWOROWS: tempCellIndex.row+=2; break;
				case LASTTWOCOLUMNS: tempCellIndex.column+=2; break;
				default:
					throw new DMLRuntimeException("unrecognized correctionLocation: "+op.aggOp.correctionLocation);	
			}
		}
		
		//prepare output
		if(result==null)
			result=new MatrixBlock(tempCellIndex.row, tempCellIndex.column, false);
		else
			result.reset(tempCellIndex.row, tempCellIndex.column, false);
		
		MatrixBlock ret = (MatrixBlock) result;
		
		//core unary aggregate
		if(    op.getNumThreads() > 1 
			&& getExactSizeOnDisk() > MIN_PAR_AGG_THRESHOLD ) 
		{
			// initialize and allocate the result
			ret.allocateDenseBlock();
			
			//multi-threaded execution of all groups 
			ArrayList<ColGroup>[] grpParts = createStaticTaskPartitioning(op.getNumThreads(), false);
			ColGroupUncompressed uc = getUncompressedColGroup();
			try {
				//compute all compressed column groups
				ExecutorService pool = Executors.newFixedThreadPool( op.getNumThreads() );
				ArrayList<UnaryAggregateTask> tasks = new ArrayList<UnaryAggregateTask>();
				for( ArrayList<ColGroup> grp : grpParts )
					tasks.add(new UnaryAggregateTask(grp, ret, op));
				pool.invokeAll(tasks);	
				pool.shutdown();
				//compute uncompressed column group in parallel (otherwise bottleneck)
				if( uc != null )
					 ret = (MatrixBlock)uc.getData().aggregateUnaryOperations(op, ret, blockingFactorRow, blockingFactorCol, indexesIn, false);					
				//aggregate partial results
				if( !(op.indexFn instanceof ReduceRow) ){
					KahanObject kbuff = new KahanObject(0,0);
					KahanPlus kplus = KahanPlus.getKahanPlusFnObject();
					for( int i=0; i<ret.getNumRows(); i++ ) {
						kbuff.set(ret.quickGetValue(i, 0), ret.quickGetValue(i, 0));
						for( UnaryAggregateTask task : tasks )
							kplus.execute2(kbuff, task.getResult().quickGetValue(i, 0));
						ret.quickSetValue(i, 0, kbuff._sum);
						ret.quickSetValue(i, 1, kbuff._correction);
					}
				}		
			}
			catch(Exception ex) {
				throw new DMLRuntimeException(ex);
			}
		}
		else {
			for (ColGroup grp : _colGroups) {
				grp.unaryAggregateOperations(op, ret);
			}
		}
		
		//drop correction if necessary
		if(op.aggOp.correctionExists && inCP)
			ret.dropLastRowsOrColums(op.aggOp.correctionLocation);
	
		//post-processing
		ret.recomputeNonZeros();
		
		return ret;
	}
	
	@Override
	public MatrixBlock transposeSelfMatrixMultOperations(MatrixBlock out, MMTSJType tstype) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ) {
			return super.transposeSelfMatrixMultOperations(out, tstype);
		}
				
		//single-threaded tsmm of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().transposeSelfMatrixMultOperations(out, tstype);
		}

		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;

		//check for transpose type
		if( tstype != MMTSJType.LEFT ) //right not supported yet
			throw new DMLRuntimeException("Invalid MMTSJ type '"+tstype.toString()+"'.");
		
		//create output matrix block
		if( out == null )
			out = new MatrixBlock(clen, clen, false);
		else
			out.reset(clen, clen, false);
		out.allocateDenseBlock();
		
		if( !isEmptyBlock(false) ) {
			//compute matrix mult
			leftMultByTransposeSelf(_colGroups, out, 0, _colGroups.size());
			
			// post-processing
			out.recomputeNonZeros();
		}
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed TSMM in "+time.stop());
		
		return out;
	}

	
	@Override
	public MatrixBlock transposeSelfMatrixMultOperations(MatrixBlock out, MMTSJType tstype, int k) 
		throws DMLRuntimeException 
	{
		//call uncompressed matrix mult if necessary
		if( !isCompressed() ){
			return super.transposeSelfMatrixMultOperations(out, tstype, k);
		}
		
		//multi-threaded tsmm of single uncompressed colgroup
		if( isSingleUncompressedGroup() ){
			return ((ColGroupUncompressed)_colGroups.get(0))
				.getData().transposeSelfMatrixMultOperations(out, tstype, k);
		}
		
		Timing time = LOG.isDebugEnabled() ? new Timing(true) : null;
		
		//check for transpose type
		if( tstype != MMTSJType.LEFT ) //right not supported yet
			throw new DMLRuntimeException("Invalid MMTSJ type '"+tstype.toString()+"'.");
		
		//create output matrix block
		if( out == null )
			out = new MatrixBlock(clen, clen, false);
		else
			out.reset(clen, clen, false);
		out.allocateDenseBlock();
		
		if( !isEmptyBlock(false) ) {
			//compute matrix mult
			try {
				ExecutorService pool = Executors.newFixedThreadPool( k );
				ArrayList<MatrixMultTransposeTask> tasks = new ArrayList<MatrixMultTransposeTask>();
				int blklen = (int)(Math.ceil((double)clen/(2*k)));
				for( int i=0; i<2*k & i*blklen<clen; i++ )
					tasks.add(new MatrixMultTransposeTask(_colGroups, out, i*blklen, Math.min((i+1)*blklen, clen)));
				List<Future<Object>> ret = pool.invokeAll(tasks);
				for( Future<Object> tret : ret )
					tret.get(); //check for errors
				pool.shutdown();
			}
			catch(Exception ex) {
				throw new DMLRuntimeException(ex);
			}
			
			// post-processing
			out.recomputeNonZeros();
		}
		
		if( LOG.isDebugEnabled() )
			LOG.debug("Compressed TSMM k="+k+" in "+time.stop());
		
		return out;
	}

	
	/**
	 * Multiply this matrix block by a column vector on the right.
	 * 
	 * @param vector
	 *            right-hand operand of the multiplication
	 * @param result
	 *            buffer to hold the result; must have the appropriate size
	 *            already
	 */
	private void rightMultByVector(MatrixBlock vector, MatrixBlock result)
		throws DMLRuntimeException 
	{
		// initialize and allocate the result
		result.allocateDenseBlock();

		// delegate matrix-vector operation to each column group
		for( ColGroup grp : _colGroups )
			if( grp instanceof ColGroupUncompressed ) //overwrites output
				grp.rightMultByVector(vector, result, 0, result.getNumRows());
		for( ColGroup grp : _colGroups )
			if( !(grp instanceof ColGroupUncompressed) ) //adds to output
				grp.rightMultByVector(vector, result, 0, result.getNumRows());
		
		// post-processing
		result.recomputeNonZeros();
	}

	/**
	 * Multi-threaded version of rightMultByVector.
	 * 
	 * @param vector
	 * @param result
	 * @param k
	 * @throws DMLRuntimeException
	 */
	private void rightMultByVector(MatrixBlock vector, MatrixBlock result, int k)
		throws DMLRuntimeException 
	{
		// initialize and allocate the result
		result.allocateDenseBlock();

		//multi-threaded execution of all groups
		try {
			ExecutorService pool = Executors.newFixedThreadPool( k );
			int rlen = getNumRows();
			int seqsz = BitmapEncoder.BITMAP_BLOCK_SZ;
			int blklen = (int)(Math.ceil((double)rlen/k));
			blklen += (blklen%seqsz != 0)?seqsz-blklen%seqsz:0;
			ArrayList<RightMatrixMultTask> tasks = new ArrayList<RightMatrixMultTask>();
			for( int i=0; i<k & i*blklen<getNumRows(); i++ )
				tasks.add(new RightMatrixMultTask(_colGroups, vector, result, i*blklen, Math.min((i+1)*blklen,rlen)));
			pool.invokeAll(tasks);	
			pool.shutdown();
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
		
		// post-processing
		result.recomputeNonZeros();
	}
	
	/**
	 * Multiply this matrix block by the transpose of a column vector (i.e.
	 * t(v)%*%X)
	 * 
	 * @param vector
	 *            left-hand operand of the multiplication
	 * @param result
	 *            buffer to hold the result; must have the appropriate size
	 *            already
	 */
	private static void leftMultByVectorTranspose(List<ColGroup> colGroups, MatrixBlock vector, MatrixBlock result, boolean doTranspose) 
		throws DMLRuntimeException 
	{
		//transpose vector if required
		MatrixBlock rowVector = vector;
		if (doTranspose) {
			rowVector = new MatrixBlock(1, vector.getNumRows(), false);
			LibMatrixReorg.transpose(vector, rowVector);
		}
		
		// initialize and allocate the result
		result.reset();
		result.allocateDenseBlock();
		
		// delegate matrix-vector operation to each column group
		for (ColGroup grp : colGroups) {			
			grp.leftMultByRowVector(rowVector, result);
		}

		// post-processing
		result.recomputeNonZeros();
	}
	
	/**
	 * Multi-thread version of leftMultByVectorTranspose.
	 * 
	 * @param vector
	 * @param result
	 * @param doTranspose
	 * @param k
	 * @throws DMLRuntimeException
	 */
	private static void leftMultByVectorTranspose(List<ColGroup> colGroups,MatrixBlock vector, MatrixBlock result, boolean doTranspose, int k) 
		throws DMLRuntimeException 
	{
		int kuc = Math.max(1, k - colGroups.size() + 1);
		
		//transpose vector if required
		MatrixBlock rowVector = vector;
		if (doTranspose) {
			rowVector = new MatrixBlock(1, vector.getNumRows(), false);
			LibMatrixReorg.transpose(vector, rowVector);
		}
		
		// initialize and allocate the result
		result.reset();
		result.allocateDenseBlock();

		//multi-threaded execution
		try {
			ExecutorService pool = Executors.newFixedThreadPool( Math.min(colGroups.size(), k) );
			ArrayList<LeftMatrixMultTask> tasks = new ArrayList<LeftMatrixMultTask>();
			for( ColGroup grp : colGroups )
				tasks.add(new LeftMatrixMultTask(grp, rowVector, result, kuc));
			pool.invokeAll(tasks);	
			pool.shutdown();
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}

		// post-processing
		result.recomputeNonZeros();
	}

	/**
	 * 
	 * @param result
	 * @throws DMLRuntimeException
	 */
	private static void leftMultByTransposeSelf(ArrayList<ColGroup> groups, MatrixBlock result, int cl, int cu)
		throws DMLRuntimeException 
	{
		final int numRows = groups.get(0).getNumRows();
		final int numGroups = groups.size();		
		
		//preallocated dense matrix block
		MatrixBlock lhs = new MatrixBlock(numRows, 1, false);
		lhs.allocateDenseBlock();
		
		//approach: for each colgroup, extract uncompressed columns one at-a-time
		//vector-matrix multiplies against remaining col groups
		for( int i=cl; i<cu; i++ ) 
		{
			//get current group and relevant col groups
			ColGroup group = groups.get(i);	
			int[] ixgroup = group.getColIndices();
			List<ColGroup> tmpList = groups.subList(i, numGroups);
			
			//for all uncompressed lhs columns vectors
			for( int j=0; j<ixgroup.length; j++ ) {
				//decompress single column
				lhs.reset(numRows, 1, false);				
				group.decompressToBlock(lhs, j);
				
				if( !lhs.isEmptyBlock(false) ) {
					//compute vector-matrix partial result
					MatrixBlock tmpret = new MatrixBlock(1,result.getNumColumns(),false);
					leftMultByVectorTranspose(tmpList, lhs, tmpret, true);								
					
					//write partial results (disjoint non-zeros)
					LinearAlgebraUtils.copyNonZerosToRowCol(result, tmpret, ixgroup[j]);	
				}
			}
		}
	}
	
	/**
	 * 
	 * @param k
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private ArrayList<ColGroup>[] createStaticTaskPartitioning(int k, boolean inclUncompressed)
	{
		// special case: single uncompressed col group
		if( _colGroups.size()==1 && _colGroups.get(0) instanceof ColGroupUncompressed ){
			return new ArrayList[0];
		}
		
		// initialize round robin col group distribution
		// (static task partitioning to reduce mem requirements/final agg)
		int numTasks = Math.min(k, _colGroups.size());
		ArrayList<ColGroup>[] grpParts = new ArrayList[numTasks];
		int pos = 0;
		for( ColGroup grp : _colGroups ){
			if( grpParts[pos]==null )
				grpParts[pos] = new ArrayList<ColGroup>();
			if( inclUncompressed || !(grp instanceof ColGroupUncompressed) ) {
				grpParts[pos].add(grp);
				pos = (pos==numTasks-1) ? 0 : pos+1;
			}
		}
		
		return grpParts;
	}
	
	/**
	 * 
	 * @return
	 */
	private ColGroupUncompressed getUncompressedColGroup()
	{
		for( ColGroup grp : _colGroups )
			if( grp instanceof ColGroupUncompressed ) 
				return (ColGroupUncompressed)grp;
		
		return null;
	}
	
	/**
	 * 
	 */
	private static class LeftMatrixMultTask implements Callable<Object> 
	{
		private ColGroup _group = null;
		private MatrixBlock _vect = null;
		private MatrixBlock _ret = null;
		private int _kuc = 1;
		
		protected LeftMatrixMultTask( ColGroup group, MatrixBlock vect, MatrixBlock ret, int kuc)  {
			_group = group;
			_vect = vect;
			_ret = ret;
			_kuc = kuc;
		}
		
		@Override
		public Object call() throws DMLRuntimeException 
		{
			// delegate matrix-vector operation to each column group
			if( _group instanceof ColGroupUncompressed && _kuc >1 && ColGroupBitmap.LOW_LEVEL_OPT )
				((ColGroupUncompressed)_group).leftMultByRowVector(_vect, _ret, _kuc);
			else
				_group.leftMultByRowVector(_vect, _ret);
			return null;
		}
	}
	
	/**
	 * 
	 */
	private static class RightMatrixMultTask implements Callable<Object> 
	{
		private ArrayList<ColGroup> _groups = null;
		private MatrixBlock _vect = null;
		private MatrixBlock _ret = null;
		private int _rl = -1;
		private int _ru = -1;
		
		protected RightMatrixMultTask( ArrayList<ColGroup> groups, MatrixBlock vect, MatrixBlock ret, int rl, int ru)  {
			_groups = groups;
			_vect = vect;
			_ret = ret;
			_rl = rl;
			_ru = ru;
		}
		
		@Override
		public Object call() throws DMLRuntimeException 
		{
			// delegate vector-matrix operation to each column group
			for( ColGroup grp : _groups )
				if( grp instanceof ColGroupUncompressed ) //overwrites output
					grp.rightMultByVector(_vect, _ret, _rl, _ru);
			for( ColGroup grp : _groups )
				if( !(grp instanceof ColGroupUncompressed) ) //adds to output
					grp.rightMultByVector(_vect, _ret, _rl, _ru);
			return null;
		}
	}
	
	private static class MatrixMultTransposeTask implements Callable<Object> 
	{
		private ArrayList<ColGroup> _groups = null;
		private MatrixBlock _ret = null;
		private int _cl = -1;
		private int _cu = -1;
		
		protected MatrixMultTransposeTask(ArrayList<ColGroup> groups, MatrixBlock ret, int cl, int cu)  {
			_groups = groups;
			_ret = ret;
			_cl = cl;
			_cu = cu;
		}
		
		@Override
		public Object call() throws DMLRuntimeException {
			leftMultByTransposeSelf(_groups, _ret, _cl, _cu);
			return null;
		}
	}
	
	private static class UnaryAggregateTask implements Callable<Object> 
	{
		private ArrayList<ColGroup> _groups = null;
		private MatrixBlock _ret = null;
		private AggregateUnaryOperator _op = null;
		
		protected UnaryAggregateTask( ArrayList<ColGroup> groups, MatrixBlock ret, AggregateUnaryOperator op)  {
			_groups = groups;
			_op = op;
			
			if( !(_op.indexFn instanceof ReduceRow) ) { //sum/rowSums
				_ret = new MatrixBlock(ret.getNumRows(), ret.getNumColumns(), false);
				_ret.allocateDenseBlock();
			}
			else { //colSums
				_ret = ret;
			}
		}
		
		@Override
		public Object call() throws DMLRuntimeException 
		{
			// delegate vector-matrix operation to each column group
			for( ColGroup grp : _groups )
				grp.unaryAggregateOperations(_op, _ret);
			return null;
		}
		
		public MatrixBlock getResult(){
			return _ret;
		}
	}
}
