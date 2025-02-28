const express = require('express');
const path = require('path');
const fs = require('fs-extra');
const ffmpeg = require('fluent-ffmpeg');
const ffmpegPath = require('ffmpeg-static');
const cors = require('cors');
const mpd_generator = require('mpd-generator');
const multer = require('multer');
const { execSync } = require('child_process');
const os = require('os');

// Set ffmpeg path
ffmpeg.setFfmpegPath(ffmpegPath);

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json());
app.use('/chunks', express.static(path.join(__dirname, 'chunks')));
app.use('/videos', express.static(path.join(__dirname, '../videos')));
app.use('/metadata', express.static(path.join(__dirname, 'metadata')));
app.use('/thumbnail', express.static(path.join(__dirname, 'thumbnail')));

// Create necessary directories
fs.ensureDirSync(path.join(__dirname, 'chunks'));
fs.ensureDirSync(path.join(__dirname, 'metadata'));
fs.ensureDirSync(path.join(__dirname, 'thumbnail'));

// Processing queue
const processingQueue = [];
let isProcessing = false;

function getLocalIP() {
  const networkInterfaces = os.networkInterfaces();
  for (const interfaceName in networkInterfaces) {
    for (const iface of networkInterfaces[interfaceName]) {
      // Skip over internal (i.e. 127.0.0.1) and non-IPv4 addresses
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

// Process the next video in the queue
async function processNextInQueue() {
  if (processingQueue.length === 0 || isProcessing) {
    return;
  }
  
  isProcessing = true;
  const task = processingQueue.shift();
  
  try {
    const result = await task.process();
    task.resolve(result);
  } catch (error) {
    task.reject(error);
  } finally {
    isProcessing = false;
    // Process the next item in the queue
    setTimeout(processNextInQueue, 1000);
  }
}

// Load metadata from files on startup
function loadAllMetadata() {
  const metadataDir = path.join(__dirname, 'metadata');
  const metadataFiles = fs.readdirSync(metadataDir).filter(file => file.endsWith('.json'));
  
  const metadata = [];
  
  for (const file of metadataFiles) {
    try {
      const filePath = path.join(metadataDir, file);
      const videoData = fs.readJSONSync(filePath);
      metadata.push(videoData);
    } catch (error) {
      console.error(`Error reading metadata file ${file}:`, error);
    }
  }
  
  // Sort by newest first (assuming ID is timestamp-based)
  return metadata.sort((a, b) => parseInt(b.id) - parseInt(a.id));
}

// API to get feed (list of videos with metadata)
app.get('/api/feed', (req, res) => {
  try {
    // Load metadata fresh from files each time
    const videosMetadata = loadAllMetadata();
    
    const page = parseInt(req.query.page) || 0;
    const pageSize = parseInt(req.query.pageSize) || 5;
    
    const startIndex = page * pageSize;
    const endIndex = startIndex + pageSize;
    
    const paginatedVideos = videosMetadata.slice(startIndex, endIndex);
    
    // Return minimal data for feed (don't include full chunk data)
    const feedVideos = paginatedVideos.map(video => ({
      id: video.id,
      title: video.title,
      description: video.description,
      duration: video.duration,
      thumbnail: video.thumbnail,
      dashManifest: `/chunks/${video.id}/manifest.mpd`,
      // Only include the first chunk data for initial playback
      firstChunk: video.chunks.length > 0 ? {
        index: 0,
        startTime: 0,
        duration: video.chunks[0].duration,
        urls: video.chunks[0].urls
      } : null
    }));
    
    res.json({
      videos: feedVideos,
      totalVideos: videosMetadata.length,
      hasMore: endIndex < videosMetadata.length
    });
  } catch (error) {
    console.error('Error fetching feed:', error);
    res.status(500).json({ error: 'Failed to fetch feed', details: error.message });
  }
});

// API to get video metadata with all chunks info
app.get('/api/videos/:videoId', (req, res) => {
  const videoId = req.params.videoId;
  
  try {
    const metadataPath = path.join(__dirname, 'metadata', `${videoId}.json`);
    
    if (!fs.existsSync(metadataPath)) {
      return res.status(404).json({ error: 'Video not found' });
    }
    
    const videoMetadata = fs.readJSONSync(metadataPath);
    res.json(videoMetadata);
  } catch (error) {
    console.error(`Error fetching video ${videoId}:`, error);
    res.status(500).json({ error: 'Failed to fetch video metadata', details: error.message });
  }
});

app.get('/getip', (req, res) => {
  try {
    const ip = getLocalIP();
    res.send(ip);
  } catch (error) {
    console.error('Error:', error);
    res.status(500).json({ error: 'Failed', details: error.message });
  }
});


const upload = multer({ dest: path.join(__dirname, '../videos/uploads/') });

// Make sure the upload directory exists
fs.ensureDirSync(path.join(__dirname, '../videos/uploads/'));

// Add this route to your index.js file
app.get('/process', (req, res) => {
  res.send(`
    <html>
      <head>
        <title>Process Video</title>
        <style>
          body { font-family: Arial, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; }
          h1 { color: #333; }
          form { margin: 20px 0; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
          .form-group { margin-bottom: 15px; }
          label { display: block; margin-bottom: 5px; font-weight: bold; }
          input[type="text"], input[type="file"] { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; }
          button { background: #4285f4; color: white; border: none; padding: 10px 15px; border-radius: 4px; cursor: pointer; }
          button:hover { background: #3367d6; }
          #result { margin-top: 20px; padding: 15px; border-radius: 5px; }
          .success { background-color: #d4edda; border-color: #c3e6cb; color: #155724; }
          .error { background-color: #f8d7da; border-color: #f5c6cb; color: #721c24; }
          .processing { background-color: #fff3cd; border-color: #ffeeba; color: #856404; }
        </style>
      </head>
      <body>
        <h1>Process Video</h1>
        <form id="uploadForm" enctype="multipart/form-data">
          <div class="form-group">
            <label for="video">Choose Video File:</label>
            <input type="file" id="video" name="video" accept="video/*" required>
          </div>
          <div class="form-group">
            <label for="title">Title:</label>
            <input type="text" id="title" name="title" placeholder="Enter video title" required>
          </div>
          <div class="form-group">
            <label for="description">Description:</label>
            <input type="text" id="description" name="description" placeholder="Enter video description">
          </div>
          <button type="submit">Upload & Process Video</button>
        </form>
        <div id="result"></div>

        <script>
          document.getElementById('uploadForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            
            const formData = new FormData();
            formData.append('video', document.getElementById('video').files[0]);
            formData.append('title', document.getElementById('title').value);
            formData.append('description', document.getElementById('description').value);
            
            const resultDiv = document.getElementById('result');
            resultDiv.className = 'processing';
            resultDiv.innerHTML = 'Uploading and processing video... This may take several minutes.';
            
            try {
              const response = await fetch('/api/upload-process', {
                method: 'POST',
                body: formData
              });
              
              if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.error || 'Failed to process video');
              }
              
              const data = await response.json();
              resultDiv.className = 'success';
              resultDiv.innerHTML = 'Success! Video has been processed.<br>' + 
                                   'Video ID: ' + data.id + '<br>' +
                                   'Duration: ' + Math.round(data.metadata.duration) + ' seconds<br>' +
                                   'Chunks: ' + data.metadata.chunks.length;
            } catch (err) {
              resultDiv.className = 'error';
              resultDiv.innerHTML = 'Error: ' + err.message;
            }
          });
        </script>
      </body>
    </html>
  `);
});

// Add this route to handle file uploads and processing
app.post('/api/upload-process', upload.single('video'), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: 'No video file uploaded' });
    }
    
    const { title, description } = req.body;
    const videoPath = `../videos/uploads/${req.file.filename}`;
    
    // Create a processing task and add it to the queue
    const processPromise = new Promise((resolve, reject) => {
      processingQueue.push({
        process: () => processVideo(videoPath, title, description),
        resolve,
        reject
      });
      
      // Start processing if not already running
      if (!isProcessing) {
        processNextInQueue();
      }
    });
    
    // Handle the response
    const result = await processPromise;
    
    // Clean up the original uploaded file to save space
    try {
      fs.unlinkSync(path.join(__dirname, videoPath));
      console.log(`Deleted original uploaded file: ${videoPath}`);
    } catch (cleanupError) {
      console.error('Error cleaning up uploaded file:', cleanupError);
      // Continue even if cleanup fails
    }
    
    res.json(result);
  } catch (error) {
    console.error('Error processing uploaded video:', error);
    res.status(500).json({ error: 'Failed to process video', details: error.message });
  }
});

// API to process a video (in real app this would be triggered by upload)
app.post('/api/process-video', (req, res) => {
  const { videoPath, title, description } = req.body;
  
  if (!videoPath) {
    return res.status(400).json({ error: 'Video path is required' });
  }
  
  // Create a processing task and add it to the queue
  const processPromise = new Promise((resolve, reject) => {
    processingQueue.push({
      process: () => processVideo(videoPath, title, description),
      resolve,
      reject
    });
    
    // Start processing if not already running
    if (!isProcessing) {
      processNextInQueue();
    }
  });
  
  // Handle the response
  processPromise
    .then(result => res.json(result))
    .catch(error => {
      console.error('Error processing video:', error);
      res.status(500).json({ error: 'Failed to process video', details: error.message });
    });
});

// Main video processing function
async function processVideo(videoPath, title, description) {
  const videoId = Date.now().toString();
  const outputDir = path.join(__dirname, 'chunks', videoId);
  const thumbnailOutputDir = path.join(__dirname, 'thumbnail', videoId);
  fs.ensureDirSync(outputDir);

  console.log(`Processing video: ${videoPath} (ID: ${videoId})`);

  try {
    // Check if the video file exists
    const fullVideoPath = path.join(__dirname, videoPath);
    if (!fs.existsSync(fullVideoPath)) {
      throw new Error(`Video file not found: ${fullVideoPath}`);
    }

    // Get original video information (resolution, bitrate, duration)
    const videoInfo = await getVideoInfo(fullVideoPath);
    const originalWidth = videoInfo.width;
    const originalHeight = videoInfo.height;
    const originalBitrate = Number(videoInfo.bitrate);
    const duration = videoInfo.duration;

    // Process video into chunks (3 seconds each)
    const chunkDuration = 3;
    const numberOfChunks = Math.ceil(duration / chunkDuration);

    // Define quality settings using the original resolution and bitrate percentages
    const qualities = [
      { 
        name: 'low', 
        // Maintain original resolution
        resolution: `${originalWidth}x${originalHeight}`, 
        // 70% of the original video bitrate
        videoBitrate: `${Math.floor(originalBitrate * 0.7 / 1000)}k`, 
        audioBitrate: '64k' 
      },
      { 
        name: 'medium', 
        resolution: `${originalWidth}x${originalHeight}`, 
        videoBitrate: `${Math.floor(originalBitrate * 0.8 / 1000)}k`, 
        audioBitrate: '96k' 
      },
      { 
        name: 'high', 
        resolution: `${originalWidth}x${originalHeight}`, 
        videoBitrate: `${Math.floor(originalBitrate / 1000)}k`, 
        audioBitrate: '128k' 
      }
    ];

    // Create manifest structure
    const manifestData = {
      id: videoId,
      title: title || 'Untitled Video',
      description: description || '',
      duration,
      dashManifest: `/chunks/${videoId}/manifest.mpd`,
      thumbnail: `/thumbnail/${videoId}/thumbnail.jpg`,
      chunks: [],
      qualities,
      createdAt: new Date().toISOString()
    };

    // Create chunks for each quality setting
    for (const quality of qualities) {
      for (let i = 0; i < numberOfChunks; i++) {
        const startTime = i * chunkDuration;
        const chunkFilename = `${videoId}_${quality.name}_chunk_${i}.mp4`;
        const chunkPath = path.join(outputDir, chunkFilename);

        try {
          await createChunk(
            fullVideoPath,
            chunkPath,
            startTime,
            chunkDuration,
            quality
          );
          console.log(`Created chunk: ${chunkFilename}`);
        } catch (err) {
          console.error(`Error creating chunk ${chunkFilename}:`, err);
        }

        // Use medium quality chunks for manifest info
        if (quality.name === 'medium') {
          manifestData.chunks.push({
            index: i,
            startTime,
            duration: Math.min(chunkDuration, duration - startTime),
            urls: {
              low: `/chunks/${videoId}/${videoId}_low_chunk_${i}.mp4`,
              medium: `/chunks/${videoId}/${videoId}_medium_chunk_${i}.mp4`,
              high: `/chunks/${videoId}/${videoId}_high_chunk_${i}.mp4`
            }
          });
        }
      }
    }

    // Generate thumbnail
    try {
      await createThumbnail(
        fullVideoPath,
        path.join(thumbnailOutputDir, 'thumbnail.jpg')
      );
      console.log('Created thumbnail');
    } catch (err) {
      console.error('Error creating thumbnail:', err);
    }

    await ensureAudioChunks(videoId, manifestData.chunks.length);
  
  // Generate the manifest
  const manifestPath = await generateDashManifest(
    videoId, 
    duration,
    manifestData.chunks,
    qualities
  );


    // Save metadata after generating DASH manifest
    const metadataPath = path.join(__dirname, 'metadata', `${videoId}.json`);
    fs.writeJSONSync(metadataPath, manifestData);

    console.log(`Video processing completed: ${videoId}`);
    return {
      id: videoId,
      message: 'Video processed successfully',
      metadata: manifestData
    };
  } catch (error) {
    console.error('Error in video processing:', error);
    throw error;
  }
}

// Helper function to get video information
function getVideoInfo(videoPath) {
  return new Promise((resolve, reject) => {
    ffmpeg.ffprobe(videoPath, (err, metadata) => {
      if (err) return reject(err);
      
      const videoStream = metadata.streams.find(s => s.codec_type === 'video');
      if (!videoStream) {
        return reject(new Error('No video stream found'));
      }
      
      resolve({
        duration: metadata.format.duration,
        width: videoStream.width,
        height: videoStream.height,
        bitrate: metadata.format.bit_rate
      });
    });
  });
}

// Helper function to create video chunk
function createChunk(inputPath, outputPath, startTime, duration, quality) {
  return new Promise((resolve, reject) => {
    ffmpeg(inputPath)
      .output(outputPath)
      .seekInput(startTime)
      .duration(duration)
      .videoCodec('libx264')
      .size(quality.resolution)
      .videoBitrate(quality.videoBitrate)
      .audioCodec('aac')
      .audioBitrate(quality.audioBitrate)
      // Add these options for DASH compatibility
      .outputOptions([
        '-movflags frag_keyframe+empty_moov+default_base_moof',
        '-g 30',               // GOP size of 30 frames
        '-keyint_min 30',      // Minimum keyframe interval
        '-sc_threshold 0',     // Disable scene change detection
        '-bf 0'                // No B-frames for better seeking
      ])
      .format('mp4')
      .on('end', () => resolve(outputPath))
      .on('error', reject)
      .run();
  });
}

// Helper function to create thumbnail
function createThumbnail(inputPath, outputPath) {
  return new Promise((resolve, reject) => {
    ffmpeg(inputPath)
      .screenshots({
        timestamps: ['50%'],
        filename: path.basename(outputPath),
        folder: path.dirname(outputPath),
        size: '640x360'
      })
      .on('end', () => resolve(outputPath))
      .on('error', reject);
  });
}

// Status API to check server health
app.get('/api/status', (req, res) => {
  res.json({
    status: 'running',
    processingQueue: processingQueue.length,
    isProcessing
  });
});

async function generateDashManifest(videoId, duration, chunks, qualities) {
  const outputDir = path.join(__dirname, 'chunks', videoId);
  const manifestPath = path.join(outputDir, 'manifest.mpd');
  
  try {
    
    const serverUrl = `http://${getLocalIP()}:3000`;

    // First generate initialization segments for each quality
    await generateInitSegments(videoId, qualities);
    // Generate audio init segment
    await generateAudioInitSegment(videoId);
    
    // Calculate segment duration in milliseconds and get max resolution
    const segmentDuration = chunks[0].duration || 3000; // Default to 3000ms if not specified
    const maxQuality = qualities.reduce((max, q) => {
      const [width, height] = q.resolution.split('x').map(Number);
      if (!max || width > max.width || height > max.height) {
        return { width, height };
      }
      return max;
    }, null);
    
    // Create MPD document
    let mpdContent = `<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" 
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
    xsi:schemaLocation="urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd" 
    profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" 
    type="static" 
    minBufferTime="PT2S" 
    mediaPresentationDuration="PT${Math.round(duration)}S">
    
    <BaseURL>${serverUrl}/chunks/${videoId}/</BaseURL>
    
    <Period id="1" start="PT0S">
        <AdaptationSet 
            id="1"
            contentType="video"
            segmentAlignment="true" 
            bitstreamSwitching="true"
            maxWidth="${maxQuality.width}" 
            maxHeight="${maxQuality.height}"
            maxFrameRate="30">`;
            
    // Add each quality as a representation
    for (const quality of qualities) {
      // Convert bitrate from string (like "400k") to numeric value
      const bitrate = parseInt(quality.videoBitrate.replace('k', '000'));
      const [width, height] = quality.resolution.split('x').map(Number);
      
      mpdContent += `
            <Representation 
                id="${quality.name}" 
                mimeType="video/mp4"
                bandwidth="${bitrate}" 
                width="${width}" 
                height="${height}" 
                codecs="avc1.4D401F"
                startWithSAP="1">
                <SegmentList duration="${segmentDuration}" timescale="1000">
                    <Initialization sourceURL="init-${quality.name}.mp4"/>`;
      
      // Add each chunk as a segment
      for (let i = 0; i < chunks.length; i++) {
        const chunkFile = `${videoId}_${quality.name}_chunk_${i}.mp4`;
        
        mpdContent += `
                    <SegmentURL media="${chunkFile}" />`;
      }
      
      mpdContent += `
                </SegmentList>
            </Representation>`;
    }
    
    // Add audio adaptation set
    mpdContent += `
        </AdaptationSet>
        
        <AdaptationSet 
            id="2"
            contentType="audio"
            segmentAlignment="true">
            <Representation 
                id="audio" 
                mimeType="audio/mp4" 
                codecs="mp4a.40.2" 
                bandwidth="128000" 
                audioSamplingRate="44100">
                <AudioChannelConfiguration 
                    schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" 
                    value="2"/>
                <SegmentList duration="${segmentDuration}" timescale="1000">
                    <Initialization sourceURL="init-audio.mp4"/>`;
                    
    // Add audio segments
    for (let i = 0; i < chunks.length; i++) {
      const audioChunkFile = `${videoId}_audio_chunk_${i}.mp4`;
      
      mpdContent += `
                    <SegmentURL media="${audioChunkFile}" />`;
    }
    
    mpdContent += `
                </SegmentList>
            </Representation>
        </AdaptationSet>
    </Period>
</MPD>`;
    
    // Write the MPD file
    fs.writeFileSync(manifestPath, mpdContent);
    console.log(`DASH Manifest generated at /chunks/${videoId}/manifest.mpd`);
    return `/chunks/${videoId}/manifest.mpd`;
  } catch (error) {
    console.error("Error generating DASH manifest:", error);
    throw error;
  }
}

async function generateInitSegments(videoId, qualities) {
  const outputDir = path.join(__dirname, 'chunks', videoId);
  const results = [];

  for (const quality of qualities) {
    // Path to the first chunk for this quality
    const firstChunkPath = path.join(outputDir, `${videoId}_${quality.name}_chunk_0.mp4`);
    // Path for the initialization segment
    const initSegmentPath = path.join(outputDir, `init-${quality.name}.mp4`);
    
    try {
      // Use ffmpeg to extract initialization segment
      const command = `ffmpeg -i "${firstChunkPath}" -c copy -map 0 -f mp4 -movflags frag_keyframe+empty_moov+default_base_moof+separate_moof -frames:v 0 "${initSegmentPath}"`;
      
      execSync(command);
      console.log(`Created initialization segment for ${quality.name} quality`);
      
      results.push({
        quality: quality.name,
        path: `/chunks/${videoId}/init-${quality.name}.mp4`
      });
    } catch (error) {
      console.error(`Error creating initialization segment for ${quality.name}:`, error);
      throw error;
    }
  }
  
  return results;
}

async function generateAudioInitSegment(videoId) {
  const outputDir = path.join(__dirname, 'chunks', videoId);
  // Use the first audio chunk as source
  const audioChunkPath = path.join(outputDir, `${videoId}_audio_chunk_0.mp4`);
  const initSegmentPath = path.join(outputDir, `init-audio.mp4`);

  try {
    // Check if audio chunk exists
    if (!fs.existsSync(audioChunkPath)) {
      // If separate audio chunks don't exist, use the first medium chunk
      const mediumChunkPath = path.join(outputDir, `${videoId}_medium_chunk_0.mp4`);
      
      // Extract audio init segment from the medium chunk
      const command = `ffmpeg -i "${mediumChunkPath}" -c copy -map 0:a -f mp4 -movflags frag_keyframe+empty_moov+default_base_moof+separate_moof -frames:a 0 "${initSegmentPath}"`;
      
      execSync(command);
      console.log(`Created audio initialization segment from medium chunk`);
    } else {
      // Use dedicated audio chunk
      const command = `ffmpeg -i "${audioChunkPath}" -c copy -map 0 -f mp4 -movflags frag_keyframe+empty_moov+default_base_moof+separate_moof -frames:a 0 "${initSegmentPath}"`;
      
      execSync(command);
      console.log(`Created audio initialization segment`);
    }
    
    return {
      path: `/chunks/${videoId}/init-audio.mp4`
    };
  } catch (error) {
    console.error(`Error creating audio initialization segment:`, error);
    throw error;
  }
}

// Helper function to ensure audio chunks exist
async function ensureAudioChunks(videoId, chunksCount) {
  const outputDir = path.join(__dirname, 'chunks', videoId);
  
  for (let i = 0; i < chunksCount; i++) {
    const audioChunkPath = path.join(outputDir, `${videoId}_audio_chunk_${i}.mp4`);
    const mediumChunkPath = path.join(outputDir, `${videoId}_medium_chunk_${i}.mp4`);
    
    // If audio chunk doesn't exist but medium chunk does, extract audio
    if (!fs.existsSync(audioChunkPath) && fs.existsSync(mediumChunkPath)) {
      try {
        const command = `ffmpeg -i "${mediumChunkPath}" -c copy -map 0:a -f mp4 -movflags frag_keyframe+empty_moov+default_base_moof "${audioChunkPath}"`;
        execSync(command);
        console.log(`Created audio chunk ${i} from medium chunk`);
      } catch (error) {
        console.error(`Error creating audio chunk ${i}:`, error);
      }
    }
  }
}



// Start server
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
  console.log(`Video processing API available at http://localhost:${PORT}/api/process-video`);
  console.log(`Feed API available at http://localhost:${PORT}/api/feed`);
});