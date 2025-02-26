// improved-process-videos.js
const fs = require('fs');
const path = require('path');
const axios = require('axios');

// Configure the path to your videos directory
const videosDir = path.join(__dirname, '../videos');
const API_URL = 'http://localhost:3000/api/process-video';

// Helper function to delay execution
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

async function processVideos() {
  try {
    // Check if the server is running
    try {
      await axios.get('http://localhost:3000/api/status');
      console.log('Server is running, proceeding with video processing');
    } catch (err) {
      console.error('Error connecting to server. Make sure the server is running at http://localhost:3000');
      process.exit(1);
    }
    
    // Read all files in the videos directory
    const files = fs.readdirSync(videosDir);
    
    // Filter for video files (common video formats)
    const videoExtensions = ['.mp4', '.mov', '.avi', '.mkv', '.webm'];
    const videoFiles = files.filter(file => 
      videoExtensions.some(ext => file.toLowerCase().endsWith(ext))
    );
    
    if (videoFiles.length === 0) {
      console.log('No video files found in the videos directory');
      return;
    }
    
    console.log(`Found ${videoFiles.length} videos to process`);
    
    // Process each video with delays between requests
    for (const [index, videoFile] of videoFiles.entries()) {
      console.log(`\nProcessing video ${index + 1}/${videoFiles.length}: ${videoFile}...`);
      
      // Calculate relative path from the server's perspective
      const videoPath = `../videos/${videoFile}`;
      
      // Generate a simple title from the filename
      const title = path.basename(videoFile, path.extname(videoFile))
        .replace(/[_-]/g, ' ')
        .replace(/\b\w/g, l => l.toUpperCase()); // Capitalize first letter of each word
      
      try {
        // Send request to process the video with a longer timeout
        console.log(`Sending ${videoFile} to processing API...`);
        const response = await axios.post(API_URL, {
          videoPath,
          title,
          description: `Video ${index + 1} in collection: ${title}`
        }, {
          timeout: 600000 // 10 minutes timeout
        });
        
        console.log(`\n✅ Successfully processed ${videoFile}`);
        console.log(`   Video ID: ${response.data.id}`);
        console.log(`   Duration: ${Math.round(response.data.metadata.duration)} seconds`);
        console.log(`   Chunks: ${response.data.metadata.chunks.length}`);
        
        // Add a delay between processing videos to allow the server to recover
        if (index < videoFiles.length - 1) {
          const waitTime = 5000;
          console.log(`\nWaiting ${waitTime/1000} seconds before processing the next video...`);
          await delay(waitTime);
        }
      } catch (err) {
        console.error(`\n❌ Error processing ${videoFile}:`);
        if (err.response) {
          console.error(`   Status: ${err.response.status}`);
          console.error(`   Message: ${JSON.stringify(err.response.data)}`);
        } else if (err.request) {
          console.error('   No response received from server (timeout or connection error)');
        } else {
          console.error(`   Message: ${err.message}`);
        }
        
        const waitTime = 10000;
        console.log(`\nWaiting ${waitTime/1000} seconds before trying the next video...`);
        await delay(waitTime); // longer delay after an error
      }
    }
    
    console.log('\n🎉 All videos have been processed!');
    
    // Get feed to show what's available
    try {
      const feedResponse = await axios.get('http://localhost:3000/api/feed');
      console.log(`\nProcessed ${feedResponse.data.totalVideos} videos in total:`);
      feedResponse.data.videos.forEach((video, index) => {
        console.log(`${index + 1}. ${video.title} (${Math.round(video.duration)}s)`);
      });
    } catch (err) {
      console.error('Could not fetch feed information');
    }
    
  } catch (err) {
    console.error('Fatal error:', err);
  }
}

processVideos();