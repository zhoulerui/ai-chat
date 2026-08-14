// 神谕百科 Jenkins Pipeline:拉取源码 → 前端构建 → 后端打包 → Docker 部署
// 用法:Jenkins 新建 Pipeline 任务,SCM 指向本仓库,Script Path = Jenkinsfile
pipeline {
    agent any

    environment {
        // 按服务器实际安装路径调整(见 README 部署章节)
        // 若工具在系统 PATH(apt/npm 全局安装),此段可删
        NODE_HOME   = "/usr/bin/node"
        MAVEN_HOME  = "/usr/bin/mvn"
        PATH        = "${NODE_HOME}/bin:${MAVEN_HOME}/bin:$PATH"
    }

    stages {
        // 1. 拉取最新代码(SCM 配置里已指定仓库与分支,无需额外步骤)
        stage('Checkout') {
            steps {
                echo "分支: ${env.GIT_BRANCH} 提交: ${env.GIT_COMMIT}"
            }
        }

        // 2. 前端构建(产物自动输出到 backend/src/main/resources/static)
        stage('前端构建') {
            steps {
                sh '''
                    cd frontend
                    npm ci || npm install      # 优先锁文件安装,失败则全量安装
                    npm run build
                '''
            }
        }

        // 3. 后端 Maven 打包(含前端 static,产出可运行 fat jar)
        stage('后端打包') {
            steps {
                sh '''
                    cd backend
                    mvn -B -DskipTests clean package
                    ls -lh target/ai-chat.jar
                '''
            }
        }

        // 4. Docker 部署(docker-compose.yml 在仓库根目录)
        stage('Docker 部署') {
            steps {
                sh '''
                    docker-compose down || true          # 停旧容器(数据在 MySQL,安全。部署阶段的命令改为旧版语法)
                    docker-compose up -d --build
                    docker-compose ps
                '''
            }
        }
    }

    post {
        success { echo '✅ 部署成功: 神谕百科 Oracle of Games' }
        failure { echo '❌ 部署失败,查看控制台日志' }
    }
}
