import os
import cv2
import time
from core.recognizer import PlateRecognizer

# 配置路径
INPUT_DIR = "input_data"
OUTPUT_DIR = "output_data"

# 确保输出目录存在
os.makedirs(OUTPUT_DIR, exist_ok=True)

# 图片和视频的扩展名
IMAGE_EXTS = ['.jpg', '.jpeg', '.png', '.bmp']
VIDEO_EXTS = ['.mp4', '.avi', '.mov', '.mkv']

def process_local_files():
    # 1. 初始化识别器
    recognizer = PlateRecognizer(yolo_model_path="models/yolo11n_plate.pt") # 请确保权重文件存在
    
    # 2. 遍历输入文件夹
    for filename in os.listdir(INPUT_DIR):
        file_path = os.path.join(INPUT_DIR, filename)
        ext = os.path.splitext(filename)[1].lower()
        
        print(f"\n[{'='*10} 开始处理文件: {filename} {'='*10}]")
        
        # --- 处理图片 ---
        if ext in IMAGE_EXTS:
            frame = cv2.imread(file_path)
            if frame is None:
                print(f"无法读取图片: {filename}")
                continue
                
            results = recognizer.process_frame(frame)
            handle_results(results, filename)
            
        # --- 处理视频 ---
        elif ext in VIDEO_EXTS:
            cap = cv2.VideoCapture(file_path)
            frame_count = 0
            
            # 为了测试效率，我们可以每隔几帧处理一次 (抽帧)，比如每 15 帧处理 1 次
            process_interval = 15 
            
            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    break
                    
                if frame_count % process_interval == 0:
                    results = recognizer.process_frame(frame)
                    if results:
                        # 对于视频，我们在文件名后加上帧号来保存截图
                        handle_results(results, f"{filename}_frame{frame_count}")
                        
                frame_count += 1
                
            cap.release()
        else:
            print(f"跳过不支持的文件格式: {filename}")

def handle_results(results, source_name):
    """
    处理并保存识别结果
    """
    if not results:
        print(f"[{source_name}] 未检测到有效车牌。")
        return
        
    for idx, res in enumerate(results):
        plate_text = res["plate_number"]
        conf = res["confidence"]
        crop_img = res["crop_image"]
        
        print(f"  -> 检测到车牌: {plate_text}, 置信度: {conf:.4f}")
        
        # 构造保存裁剪图片的名称: 车牌号_置信度_来源名称_索引.jpg
        # 注意替换掉来源名称中可能导致路径错误的点
        safe_source_name = source_name.replace(".", "_")
        save_name = f"{plate_text}_{int(conf*100)}_{safe_source_name}_{idx}.jpg"
        save_path = os.path.join(OUTPUT_DIR, save_name)
        
        # 保存裁剪图片到本地
        cv2.imwrite(save_path, crop_img)
        print(f"  -> 裁剪图已保存至: {save_path}")

if __name__ == "__main__":
    # 运行前请确保 input_data 文件夹里有测试的图片或视频
    if not os.path.exists(INPUT_DIR) or len(os.listdir(INPUT_DIR)) == 0:
        print(f"请先在 {INPUT_DIR} 文件夹中放入测试用的图片或视频文件！")
    else:
        process_local_files()